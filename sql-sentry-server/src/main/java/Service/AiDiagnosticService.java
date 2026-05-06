package Service;

import Service.concurrent.DiagnosticExecutorManager;
import Service.config.AiProperties;
import Service.diagnosis.DiagnosticFinding;
import Service.metrics.DiagnosticsMetricsService;
import Service.rewrite.SqlRewriteMappingService;
import Service.rewrite.SqlSecurityChecker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiDiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(AiDiagnosticService.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final Pattern JSON_CODE_BLOCK_PATTERN = Pattern.compile("(?is)```(?:json)?\\s*(\\{.*\\})\\s*```");

    private final SqlHeuristicAnalyzer sqlHeuristicAnalyzer;
    private final DiagnosticExecutorManager executorManager;
    private final DiagnosticsMetricsService metricsService;
    private final AiProperties aiProperties;
    private final SqlSecurityChecker sqlSecurityChecker;
    private final SqlRewriteMappingService sqlRewriteMappingService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient;

    public AiDiagnosticService(
            SqlHeuristicAnalyzer sqlHeuristicAnalyzer,
            DiagnosticExecutorManager executorManager,
            DiagnosticsMetricsService metricsService,
            AiProperties aiProperties,
            SqlSecurityChecker sqlSecurityChecker,
            SqlRewriteMappingService sqlRewriteMappingService) {
        this.sqlHeuristicAnalyzer = sqlHeuristicAnalyzer;
        this.executorManager = executorManager;
        this.metricsService = metricsService;
        this.aiProperties = aiProperties;
        this.sqlSecurityChecker = sqlSecurityChecker;
        this.sqlRewriteMappingService = sqlRewriteMappingService;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(aiProperties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(aiProperties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(aiProperties.getWriteTimeoutMs(), TimeUnit.MILLISECONDS)
                .callTimeout(aiProperties.getCallTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    public boolean submitSlowQueryDiagnosis(String slowSql, String explainPlan, String source, String database, String traceId) {
        return submitSlowQueryDiagnosis(slowSql, explainPlan, source, database, traceId, null, null, null);
    }

    public boolean submitSlowQueryDiagnosis(
            String slowSql,
            String explainPlan,
            String source,
            String database,
            String traceId,
            String requestModel,
            String requestApiUrl,
            String requestApiKey) {
        RequestAiOptions requestAiOptions = resolveRequestAiOptions(requestModel, requestApiUrl, requestApiKey);
        if (!hasText(slowSql) || !hasText(requestAiOptions.getApiKey()) || !hasText(requestAiOptions.getApiUrl())) {
            return false;
        }

        metricsService.incrementDiagnosticRequests();
        String analyzeTask = "capture-analyze-" + sqlRewriteMappingService.fingerprint(slowSql);

        CompletableFuture<SqlHeuristicAnalyzer.AnalysisSnapshot> analysisFuture = executorManager.submitRule(analyzeTask, () -> {
            long startNanos = System.nanoTime();
            try {
                return sqlHeuristicAnalyzer.analyze(slowSql, explainPlan);
            } finally {
                metricsService.recordRuleEngineDuration(System.nanoTime() - startNanos);
            }
        });

        analysisFuture
                .thenCompose(analysis -> executorManager.submitLlm(
                        "rewrite-" + sqlRewriteMappingService.fingerprint(analysis.getNormalizedSql()),
                        () -> {
                            long startNanos = System.nanoTime();
                            try {
                                diagnoseAndMaybePublishRewrite(analysis, requestAiOptions);
                                return Boolean.TRUE;
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            } finally {
                                metricsService.recordLlmDuration(System.nanoTime() - startNanos);
                            }
                        }))
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        return;
                    }
                    metricsService.incrementAiFailures();
                    log.warn(
                            "自动慢 SQL 诊断失败，source={}, database={}, traceId={}, reason={}",
                            safeValue(source),
                            safeValue(database),
                            safeValue(traceId),
                            error.toString());
                });

        log.info(
                "已调度慢 SQL 诊断，source={}, database={}, traceId={}, fingerprint={}",
                safeValue(source),
                safeValue(database),
                safeValue(traceId),
                sqlRewriteMappingService.fingerprint(slowSql));
        return true;
    }

    private void diagnoseAndMaybePublishRewrite(
            SqlHeuristicAnalyzer.AnalysisSnapshot analysis,
            RequestAiOptions requestAiOptions) throws IOException {
        String fingerprint = sqlRewriteMappingService.fingerprint(analysis.getNormalizedSql());
        if (sqlRewriteMappingService.findByFingerprint(fingerprint) != null) {
            return;
        }
        if (!sqlSecurityChecker.isAutoRewriteEligible(analysis.getNormalizedSql())) {
            return;
        }

        AiRewriteSuggestion suggestion = callModel(analysis, requestAiOptions, fingerprint);
        SqlSecurityChecker.SecurityDecision securityDecision = sqlSecurityChecker.checkRewriteCandidate(
                analysis.getNormalizedSql(),
                suggestion.getOptimizedSql());
        if (!securityDecision.isSafe()) {
            metricsService.incrementRewriteMappingsRejected();
            log.info(
                    "拒绝 AI 改写，fingerprint={}, reason={}",
                    fingerprint,
                    securityDecision.getReason());
            return;
        }

        sqlRewriteMappingService.storeApprovedRewrite(
                fingerprint,
                analysis,
                securityDecision.getNormalizedSql(),
                buildRewriteSummary(suggestion),
                buildRewriteAdviceSummary(suggestion, analysis));
    }

    private AiRewriteSuggestion callModel(
            SqlHeuristicAnalyzer.AnalysisSnapshot analysis,
            RequestAiOptions requestAiOptions,
            String fingerprint) throws IOException {
        String payload = buildRequestBody(analysis, requestAiOptions);
        Request request = new Request.Builder()
                .url(requestAiOptions.getApiUrl())
                .addHeader("Authorization", "Bearer " + requestAiOptions.getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload, JSON_MEDIA_TYPE))
                .build();

        log.info(
                "AI model call start, fingerprint={}, model={}, endpoint={}",
                fingerprint,
                safeValue(requestAiOptions.getModel()),
                request.url());

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("模型调用失败，code=" + response.code() + ", body=" + limitLength(responseBody, 300));
            }
            return parseSuggestion(extractModelContent(responseBody));
        }
    }

    private String buildRequestBody(
            SqlHeuristicAnalyzer.AnalysisSnapshot analysis,
            RequestAiOptions requestAiOptions) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", requestAiOptions.getModel());
        root.put("stream", false);
        if (aiProperties.getTemperature() != null) {
            root.put("temperature", aiProperties.getTemperature());
        }
        if (aiProperties.getMaxTokens() > 0) {
            root.put("max_tokens", aiProperties.getMaxTokens());
        }
        if (hasText(aiProperties.getThinkingType())) {
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", aiProperties.getThinkingType().trim());
        }

        ArrayNode messages = root.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content",
                "只返回紧凑 JSON，字段只能包含 summary、optimizationPoints、optimizedSql。"
                        + "请使用简洁的简体中文。若不存在安全改写方案，请将 optimizedSql 置空。"
                        + "不要新增或删除 LIMIT/OFFSET、谓词、投影列、JOIN、GROUP BY 或 ORDER BY。"
                        + "不要输出 Markdown，也不要输出多语句 SQL。");

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", buildUserPrompt(analysis));
        return objectMapper.writeValueAsString(root);
    }

    private String buildUserPrompt(SqlHeuristicAnalyzer.AnalysisSnapshot analysis) {
        StringBuilder builder = new StringBuilder();
        builder.append("待改写 SQL：\n").append(analysis.getNormalizedSql()).append("\n");
        builder.append("不要把 <str>、<num>、<email>、<phone>、<secret> 这类脱敏占位符直接复制到 optimizedSql 中。\n");
        builder.append("如果唯一优化只是增加 LIMIT、建议加索引，或改变结果集大小，请保持 optimizedSql 为空。\n");
        builder.append("风险：")
                .append(analysis.getRiskLevel())
                .append("/")
                .append(analysis.getRiskScore())
                .append("。")
                .append(limitLength(analysis.getSummary(), 120))
                .append("\n");
        builder.append("诊断结论：\n");

        for (DiagnosticFinding finding : analysis.getFindings()) {
            builder.append("- [")
                    .append(finding.getSeverity())
                    .append("] ")
                    .append(finding.getTitle())
                    .append(" | 证据：")
                    .append(limitLength(finding.getEvidence(), 120))
                    .append(" | 建议：")
                    .append(limitLength(finding.getSuggestion(), 120))
                    .append("\n");
        }

        if (analysis.isExplainProvided()) {
            builder.append("EXPLAIN:\n")
                    .append(limitLength(analysis.getNormalizedExplainPlan(), 1200))
                    .append("\n");
        }

        builder.append("请保持占位符稳定，只返回紧凑 JSON。");
        return builder.toString();
    }

    private String extractModelContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choiceNode = root.path("choices").path(0);
        JsonNode messageNode = choiceNode.path("message");
        String content = contentText(messageNode.path("content"));
        if (hasText(content)) {
            return content.trim();
        }

        String reasoningContent = contentText(messageNode.path("reasoning_content"));
        if (hasText(reasoningContent)) {
            return reasoningContent.trim();
        }

        String apiError = root.path("error").path("message").asText(null);
        if (hasText(apiError)) {
            throw new IOException(apiError.trim());
        }
        throw new IOException("模型返回内容为空");
    }

    private String contentText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            Iterator<JsonNode> iterator = node.elements();
            while (iterator.hasNext()) {
                String text = contentText(iterator.next());
                if (hasText(text)) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return builder.toString();
        }
        if (node.isObject()) {
            String text = contentText(node.path("text"));
            if (hasText(text)) {
                return text;
            }
            return contentText(node.path("content"));
        }
        return node.asText(null);
    }

    private AiRewriteSuggestion parseSuggestion(String content) throws IOException {
        String normalizedContent = hasText(content) ? content.trim() : "";
        if (!hasText(normalizedContent)) {
            return new AiRewriteSuggestion("AI 未返回有效建议", new ArrayList<String>(), "");
        }

        JsonNode jsonNode = tryParseJsonPayload(normalizedContent);
        if (jsonNode == null || !jsonNode.isObject()) {
            List<String> optimizationPoints = new ArrayList<String>();
            optimizationPoints.add(limitLength(normalizedContent, 240));
            return new AiRewriteSuggestion("AI 返回内容不是合法 JSON", optimizationPoints, "");
        }

        String summary = textValue(jsonNode.path("summary"));
        if (!hasText(summary)) {
            summary = "AI 已生成改写建议";
        }

        List<String> optimizationPoints = new ArrayList<String>();
        JsonNode pointsNode = jsonNode.path("optimizationPoints");
        if (pointsNode.isArray()) {
            Iterator<JsonNode> iterator = pointsNode.elements();
            while (iterator.hasNext() && optimizationPoints.size() < 3) {
                String value = textValue(iterator.next());
                if (hasText(value)) {
                    optimizationPoints.add(value);
                }
            }
        }

        return new AiRewriteSuggestion(summary, optimizationPoints, textValue(jsonNode.path("optimizedSql")));
    }

    private JsonNode tryParseJsonPayload(String content) throws IOException {
        try {
            return objectMapper.readTree(content);
        } catch (IOException ignored) {
        }

        Matcher matcher = JSON_CODE_BLOCK_PATTERN.matcher(content);
        if (matcher.find()) {
            return objectMapper.readTree(matcher.group(1));
        }

        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return objectMapper.readTree(content.substring(start, end + 1));
        }
        return null;
    }

    private String buildRewriteAdviceSummary(AiRewriteSuggestion suggestion, SqlHeuristicAnalyzer.AnalysisSnapshot analysis) {
        List<String> pieces = new ArrayList<String>();
        for (String point : suggestion.getOptimizationPoints()) {
            String normalized = normalizeDisplayText(point, 24, null);
            if (hasText(normalized)) {
                pieces.add(normalized);
            }
            if (pieces.size() >= 2) {
                break;
            }
        }

        if (pieces.isEmpty()) {
            pieces.add(analysis.isExplainProvided() ? "先对比 EXPLAIN" : "先核对结果和执行计划");
        } else if (analysis.isExplainProvided()) {
            pieces.add("上线前再次核对 EXPLAIN");
        }
        return normalizeDisplayText(String.join("；", pieces), 90, "先核对结果和执行计划");
    }

    private String buildRewriteSummary(AiRewriteSuggestion suggestion) {
        return normalizeDisplayText(suggestion.getSummary(), 40, "AI 已生成改写方案");
    }

    private RequestAiOptions resolveRequestAiOptions(String requestModel, String requestApiUrl, String requestApiKey) {
        String model = hasText(requestModel) ? requestModel.trim() : safeTrim(aiProperties.getModel());
        String apiUrl = hasText(requestApiUrl) ? requestApiUrl.trim() : safeTrim(aiProperties.getApiUrl());
        String apiKey = hasText(requestApiKey) ? requestApiKey.trim() : safeTrim(aiProperties.getApiKey());
        return new RequestAiOptions(model, apiUrl, apiKey);
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private String normalizeDisplayText(String text, int limit, String fallback) {
        if (!hasText(text)) {
            return fallback;
        }
        String compact = text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        if (compact.length() <= limit) {
            return compact;
        }
        return compact.substring(0, limit) + "...";
    }

    private String limitLength(String text, int limit) {
        if (!hasText(text) || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String safeTrim(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String safeValue(String value) {
        return hasText(value) ? value.trim() : "-";
    }

    @PreDestroy
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    private static final class AiRewriteSuggestion {
        private final String summary;
        private final List<String> optimizationPoints;
        private final String optimizedSql;

        private AiRewriteSuggestion(String summary, List<String> optimizationPoints, String optimizedSql) {
            this.summary = summary;
            this.optimizationPoints = optimizationPoints;
            this.optimizedSql = optimizedSql;
        }

        private String getSummary() {
            return summary;
        }

        private List<String> getOptimizationPoints() {
            return optimizationPoints;
        }

        private String getOptimizedSql() {
            return optimizedSql;
        }
    }

    private static final class RequestAiOptions {
        private final String model;
        private final String apiUrl;
        private final String apiKey;

        private RequestAiOptions(String model, String apiUrl, String apiKey) {
            this.model = model;
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
        }

        private String getModel() {
            return model;
        }

        private String getApiUrl() {
            return apiUrl;
        }

        private String getApiKey() {
            return apiKey;
        }
    }
}
