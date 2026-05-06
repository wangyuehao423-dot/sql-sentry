package Service;

import Service.cache.CachedJsonValue;
import Service.cache.JsonCacheStore;
import Service.diagnosis.DiagnosticFinding;
import Service.rewrite.SqlRewriteMappingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Service
public class SqlCaptureService {

    private static final Logger log = LoggerFactory.getLogger(SqlCaptureService.class);
    private static final String RECENT_CAPTURE_KEY = "sql_capture:recent";
    private static final int CAPTURE_LIMIT = 30;

    private final JsonCacheStore cacheStore;
    private final SqlHeuristicAnalyzer sqlHeuristicAnalyzer;
    private final AiDiagnosticService aiDiagnosticService;
    private final SqlRewriteMappingService sqlRewriteMappingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SqlCaptureService(
            JsonCacheStore cacheStore,
            SqlHeuristicAnalyzer sqlHeuristicAnalyzer,
            AiDiagnosticService aiDiagnosticService,
            SqlRewriteMappingService sqlRewriteMappingService) {
        this.cacheStore = cacheStore;
        this.sqlHeuristicAnalyzer = sqlHeuristicAnalyzer;
        this.aiDiagnosticService = aiDiagnosticService;
        this.sqlRewriteMappingService = sqlRewriteMappingService;
    }

    public ObjectNode recordCapture(CaptureRequest captureRequest) {
        SqlHeuristicAnalyzer.AnalysisSnapshot analysis = sqlHeuristicAnalyzer.analyze(
                captureRequest.getSql(),
                captureRequest.getExplainPlan()
        );
        String fingerprint = sqlRewriteMappingService.fingerprint(analysis.getNormalizedSql());
        CachedJsonValue existingRewrite = sqlRewriteMappingService.findByFingerprint(fingerprint);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("captureId", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        payload.put("capturedAt", Instant.now().toString());
        payload.put("source", defaultValue(captureRequest.getSource(), "collector"));
        payload.put("database", defaultValue(captureRequest.getDatabase(), "unknown"));
        payload.put("traceId", defaultValue(captureRequest.getTraceId(), ""));

        if (captureRequest.getElapsedMs() != null) {
            payload.put("elapsedMs", captureRequest.getElapsedMs());
        } else {
            payload.putNull("elapsedMs");
        }

        payload.put("sql", analysis.getNormalizedSql());
        payload.put("fingerprint", fingerprint);
        payload.put("sqlPreview", buildSqlPreview(analysis.getNormalizedSql()));
        payload.put("explainProvided", analysis.isExplainProvided());
        if (analysis.isExplainProvided()) {
            payload.put("explainPlan", analysis.getNormalizedExplainPlan());
        } else {
            payload.putNull("explainPlan");
        }
        payload.put("riskScore", analysis.getRiskScore());
        payload.put("riskLevel", analysis.getRiskLevel());
        payload.put("riskLabel", toRiskLabel(analysis.getRiskLevel()));
        payload.put("findingCount", analysis.getFindings().size());

        ArrayNode findings = payload.putArray("findings");
        for (DiagnosticFinding finding : analysis.getFindings()) {
            ObjectNode item = findings.addObject();
            item.put("code", finding.getTitle());
            item.put("title", toFindingLabel(finding.getTitle()));
            item.put("severity", finding.getSeverity());
            item.put("severityLabel", toRiskLabel(finding.getSeverity()));
            String message = buildFindingMessage(finding);
            item.put("message", message);
            item.put("evidence", message);
        }

        boolean diagnosisScheduled = false;
        if (existingRewrite == null) {
            diagnosisScheduled = aiDiagnosticService.submitSlowQueryDiagnosis(
                    analysis.getNormalizedSql(),
                    analysis.getNormalizedExplainPlan(),
                    captureRequest.getSource(),
                    captureRequest.getDatabase(),
                    captureRequest.getTraceId(),
                    captureRequest.getModel(),
                    captureRequest.getApiUrl(),
                    captureRequest.getApiKey()
            );
        }
        payload.put("diagnosisScheduled", diagnosisScheduled);
        applyClientView(payload, analysis, existingRewrite == null ? null : existingRewrite.getPayload(), diagnosisScheduled);

        persistCapture(payload);
        return payload;
    }

    public JsonNode listRecentCaptures(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, CAPTURE_LIMIT));
        return cacheStore.range(RECENT_CAPTURE_KEY, safeLimit);
    }

    private void persistCapture(ObjectNode payload) {
        try {
            cacheStore.pushLeft(RECENT_CAPTURE_KEY, payload, CAPTURE_LIMIT);
        } catch (IOException ex) {
            log.warn(
                    "Failed to persist recent SQL capture, captureId={}, reason={}",
                    payload.path("captureId").asText(),
                    ex.toString());
        }
    }

    private void applyClientView(
            ObjectNode payload,
            SqlHeuristicAnalyzer.AnalysisSnapshot analysis,
            JsonNode approvedRewrite,
            boolean diagnosisScheduled) {
        if (approvedRewrite != null && approvedRewrite.isObject()) {
            applyAiClientView(payload, analysis, approvedRewrite);
            return;
        }
        applyRuleClientView(payload, analysis, diagnosisScheduled);
    }

    private void applyAiClientView(
            ObjectNode payload,
            SqlHeuristicAnalyzer.AnalysisSnapshot analysis,
            JsonNode approvedRewrite) {
        payload.put("diagnosisSource", "ai");
        payload.put("diagnosisStatus", "ai_ready");
        payload.put("headline", "AI 改写方案已就绪");
        payload.put("summary", shortText(textValue(approvedRewrite.path("summary")), 48, "AI 已生成可用的改写方案。"));
        payload.put("advice", shortText(textValue(approvedRewrite.path("advice")), 96, "启用改写前请先对比 EXPLAIN。"));

        String optimizedSql = textValue(approvedRewrite.path("optimizedSql"));
        if (hasText(optimizedSql)) {
            payload.put("optimizedSqlPreview", buildSqlPreview(optimizedSql));
        } else {
            payload.putNull("optimizedSqlPreview");
        }

        ArrayNode highlights = payload.putArray("highlights");
        highlights.add("风险：" + toRiskLabel(analysis.getRiskLevel()));
        if (hasText(optimizedSql)) {
            highlights.add("改写：" + buildSqlPreview(optimizedSql));
        }
    }

    private void applyRuleClientView(
            ObjectNode payload,
            SqlHeuristicAnalyzer.AnalysisSnapshot analysis,
            boolean diagnosisScheduled) {
        payload.put("diagnosisSource", "rule");
        payload.put("diagnosisStatus", diagnosisScheduled ? "ai_pending" : "rule_only");
        payload.put("headline", buildRuleHeadline(analysis));
        payload.put("summary", buildRuleSummary(analysis, diagnosisScheduled));
        payload.put("advice", buildRuleAdvice(analysis, diagnosisScheduled));
        payload.putNull("optimizedSqlPreview");

        ArrayNode highlights = payload.putArray("highlights");
        int added = 0;
        for (DiagnosticFinding finding : analysis.getFindings()) {
            if (added >= 3) {
                break;
            }
            highlights.add(toFindingLabel(finding.getTitle()));
            added++;
        }
        if (added == 0) {
            highlights.add("未命中明显高风险规则");
        }
    }

    private String buildSqlPreview(String sql) {
        String compact = sql.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 120) {
            return compact;
        }
        return compact.substring(0, 120) + "...";
    }

    private String defaultValue(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String buildRuleHeadline(SqlHeuristicAnalyzer.AnalysisSnapshot analysis) {
        if (analysis.getFindings().isEmpty()) {
            return "规则诊断已完成";
        }
        return toRiskLabel(analysis.getRiskLevel()) + "：" + toFindingLabel(analysis.getFindings().get(0).getTitle());
    }

    private String buildRuleSummary(SqlHeuristicAnalyzer.AnalysisSnapshot analysis, boolean diagnosisScheduled) {
        if (analysis.getFindings().isEmpty()) {
            return diagnosisScheduled
                    ? "本地规则未发现明显高风险，已提交 AI 诊断。"
                    : "本地规则未发现明显高风险。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("共发现 ")
                .append(analysis.getFindings().size())
                .append(" 个问题，优先关注“")
                .append(toFindingLabel(analysis.getFindings().get(0).getTitle()))
                .append("”。");
        if (diagnosisScheduled) {
            builder.append(" 已提交 AI 诊断。");
        }
        return shortText(builder.toString(), 72, builder.toString());
    }

    private String buildRuleAdvice(SqlHeuristicAnalyzer.AnalysisSnapshot analysis, boolean diagnosisScheduled) {
        String advice;
        if (!analysis.getFindings().isEmpty()) {
            advice = buildFindingAdvice(analysis.getFindings().get(0));
        } else if (!analysis.getRecommendedChecks().isEmpty()) {
            advice = analysis.getRecommendedChecks().get(0);
        } else {
            advice = "请优先检查 EXPLAIN、索引和过滤条件。";
        }

        if (diagnosisScheduled) {
            return shortText(advice + " AI 结果生成后再复核改写方案。", 96, advice);
        }
        return shortText(advice, 96, advice);
    }

    private String buildFindingMessage(DiagnosticFinding finding) {
        String message = hasText(finding.getEvidence()) ? finding.getEvidence() : finding.getSuggestion();
        return shortText(message, 80, toFindingLabel(finding.getTitle()));
    }

    private String buildFindingAdvice(DiagnosticFinding finding) {
        String title = finding.getTitle();
        if ("LIKE 前导通配".equals(title)) {
            return "避免使用前导通配搜索，优先改为前缀匹配或全文检索。";
        }
        if ("排序压力".equals(title)) {
            return "检查 ORDER BY 是否能够命中同一个联合索引。";
        }
        if ("深分页".equals(title)) {
            return "把大偏移量分页替换为 Keyset 分页或主键分页。";
        }
        if ("变更语句缺少 WHERE".equals(title)) {
            return "执行 UPDATE 或 DELETE 前先补充精确的 WHERE 条件。";
        }
        if ("全表扫描".equals(title)) {
            return "检查过滤条件和索引使用情况，避免发生全表扫描。";
        }
        if ("检测到临时表".equals(title) || "检测到文件排序".equals(title)) {
            return "优先检查排序/分组字段和索引顺序。";
        }
        if ("查询了全部列".equals(title)) {
            return "避免 SELECT *，只查询真正需要的列。";
        }
        if ("OR 条件谓词".equals(title)) {
            return "考虑改写为 UNION ALL，或确保每个分支都能走索引。";
        }
        if ("索引列上使用函数".equals(title)) {
            return "不要直接在索引过滤列上套函数。";
        }
        if ("存在子查询路径".equals(title)) {
            return "考虑把子查询改写为 JOIN 或半连接。";
        }
        if ("多表关联过多".equals(title)) {
            return "检查 JOIN 顺序、索引情况和中间结果规模。";
        }
        if ("扫描行数过大".equals(title)) {
            return "先压缩扫描行数，再考虑 SQL 改写。";
        }
        return shortText(finding.getSuggestion(), 90, "请优先检查执行计划和索引使用情况。");
    }

    private String toFindingLabel(String title) {
        if ("LIKE 前导通配".equals(title)) {
            return "LIKE 前导通配";
        }
        if ("排序压力".equals(title)) {
            return "排序压力";
        }
        if ("深分页".equals(title)) {
            return "深分页";
        }
        if ("变更语句缺少 WHERE".equals(title)) {
            return "变更语句缺少 WHERE";
        }
        if ("全表扫描".equals(title)) {
            return "全表扫描";
        }
        if ("检测到临时表".equals(title)) {
            return "临时表";
        }
        if ("检测到文件排序".equals(title)) {
            return "文件排序";
        }
        if ("查询了全部列".equals(title)) {
            return "查询了全部列";
        }
        if ("OR 条件谓词".equals(title)) {
            return "OR 条件谓词";
        }
        if ("索引列上使用函数".equals(title)) {
            return "索引列上使用函数";
        }
        if ("存在子查询路径".equals(title)) {
            return "存在子查询路径";
        }
        if ("多表关联过多".equals(title)) {
            return "多表关联过多";
        }
        if ("扫描行数过大".equals(title)) {
            return "扫描行数过大";
        }
        if ("未命中明显高风险规则".equals(title)) {
            return "未命中明显高风险规则";
        }
        return shortText(title, 24, "规则命中");
    }

    private String toRiskLabel(String riskLevel) {
        if ("critical".equalsIgnoreCase(riskLevel)) {
            return "严重";
        }
        if ("high".equalsIgnoreCase(riskLevel)) {
            return "高";
        }
        if ("medium".equalsIgnoreCase(riskLevel)) {
            return "中";
        }
        return "低";
    }

    private String shortText(String value, int limit, String fallback) {
        if (!hasText(value)) {
            return fallback;
        }
        String compact = value.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        if (compact.length() <= limit) {
            return compact;
        }
        return compact.substring(0, limit) + "...";
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static final class CaptureRequest {
        private final String sql;
        private final String explainPlan;
        private final String source;
        private final String database;
        private final String traceId;
        private final Long elapsedMs;
        private final String model;
        private final String apiUrl;
        private final String apiKey;

        public CaptureRequest(
                String sql,
                String explainPlan,
                String source,
                String database,
                String traceId,
                Long elapsedMs,
                String model,
                String apiUrl,
                String apiKey) {
            this.sql = sql;
            this.explainPlan = explainPlan;
            this.source = source;
            this.database = database;
            this.traceId = traceId;
            this.elapsedMs = elapsedMs;
            this.model = model;
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
        }

        public String getSql() {
            return sql;
        }

        public String getExplainPlan() {
            return explainPlan;
        }

        public String getSource() {
            return source;
        }

        public String getDatabase() {
            return database;
        }

        public String getTraceId() {
            return traceId;
        }

        public Long getElapsedMs() {
            return elapsedMs;
        }

        public String getModel() {
            return model;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public String getApiKey() {
            return apiKey;
        }
    }
}
