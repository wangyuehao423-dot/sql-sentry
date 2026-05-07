package com.yuehao.sqlsentry.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import com.yuehao.sqlsentry.model.SqlClientDiagnosis;
import com.yuehao.sqlsentry.rewrite.SqlFingerprintUtils;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;

public class SqlCaptureReporter {

    private static final Logger log = LoggerFactory.getLogger(SqlCaptureReporter.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final SqlSentryProperties properties;
    private final OkHttpClient okHttpClient;
    private final SqlSentryClientViewStore clientViewStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SqlCaptureReporter(SqlSentryProperties properties, OkHttpClient okHttpClient) {
        this(properties, okHttpClient, new SqlSentryClientViewStore());
    }

    public SqlCaptureReporter(
            SqlSentryProperties properties,
            OkHttpClient okHttpClient,
            SqlSentryClientViewStore clientViewStore) {
        this.properties = properties;
        this.okHttpClient = okHttpClient;
        this.clientViewStore = clientViewStore;
    }

    public void reportSlowSql(String sql, long elapsedMs) {
        reportSlowSql(sql, elapsedMs, (SqlCapturePrintContext) null);
    }

    public void reportSlowSql(String sql, long elapsedMs, String mappedStatementId) {
        reportSlowSql(sql, elapsedMs, new SqlCapturePrintContext(mappedStatementId, null, false, null));
    }

    public void reportSlowSql(String sql, long elapsedMs, SqlCapturePrintContext printContext) {
        if (!properties.isEnabled()
                || !properties.isCaptureEnabled()
                || !hasText(properties.getServerBaseUrl())
                || !hasText(sql)) {
            return;
        }

        String normalizedSql = SqlFingerprintUtils.normalize(sql);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("sql", normalizedSql);
        payload.put("fingerprint", SqlFingerprintUtils.fingerprint(normalizedSql));
        payload.put("elapsedMs", elapsedMs);
        payload.put("traceId", defaultValue(MDC.get("traceId"), ""));
        payload.put("source", defaultValue(properties.getSource(), "default-service"));
        payload.put("database", defaultValue(properties.getDatabase(), "default"));
        if (hasText(properties.getAi().getModel())) {
            payload.put("model", properties.getAi().getModel().trim());
        }
        if (hasText(properties.getAi().getApiUrl())) {
            payload.put("apiUrl", properties.getAi().getApiUrl().trim());
        }
        if (hasText(properties.getAi().getApiKey())) {
            payload.put("apiKey", properties.getAi().getApiKey().trim());
        }

        Request request = new Request.Builder()
                .url(buildUrl(properties.getCapturePath()))
                .post(RequestBody.create(payload.toString(), JSON_MEDIA_TYPE))
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.debug("Failed to report slow SQL to server: {}", e.toString());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (Response safeResponse = response) {
                    if (!safeResponse.isSuccessful() || safeResponse.body() == null) {
                        return;
                    }

                    JsonNode root = objectMapper.readTree(safeResponse.body().string());
                    String fingerprint = textValue(root.path("fingerprint"));
                    if (!hasText(fingerprint)) {
                        fingerprint = payload.path("fingerprint").asText();
                    }
                    SqlClientDiagnosis diagnosis = toClientDiagnosis(fingerprint, root);
                    clientViewStore.save(diagnosis);
                    if (printContext == null || !printContext.isAutoReplaced()) {
                        printClientDiagnosis(diagnosis, printContext);
                    }
                }
            }
        });
    }

    private String buildUrl(String path) {
        String baseUrl = properties.getServerBaseUrl().trim();
        String normalizedPath = hasText(path) ? path.trim() : "/api/sql/captures";
        if (baseUrl.endsWith("/") && normalizedPath.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + normalizedPath;
        }
        if (!baseUrl.endsWith("/") && !normalizedPath.startsWith("/")) {
            return baseUrl + "/" + normalizedPath;
        }
        return baseUrl + normalizedPath;
    }

    private String defaultValue(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private SqlClientDiagnosis toClientDiagnosis(String fingerprint, JsonNode root) {
        String status = textValue(root.path("diagnosisStatus"));
        String stage = resolveStage(status);
        String headline = resolveHeadline(status, textValue(root.path("headline")), root);
        String summary = resolveSummary(status, textValue(root.path("headline")), textValue(root.path("summary")));
        String advice = resolveAdvice(textValue(root.path("headline")), textValue(root.path("advice")));
        String optimizedSqlPreview = textValue(root.path("optimizedSqlPreview"));
        return new SqlClientDiagnosis(fingerprint, stage, status, headline, summary, advice, optimizedSqlPreview);
    }

    private void printClientDiagnosis(SqlClientDiagnosis diagnosis, SqlCapturePrintContext printContext) {
        if (diagnosis == null) {
            return;
        }

        System.out.println("[SQL Sentry] \u7ed3\u8bba: " + buildConclusion(diagnosis));

        String exampleSql = buildCopyableExampleSql(diagnosis, printContext);
        if (hasText(exampleSql)) {
            System.out.println("[SQL Sentry] \u793a\u4f8b: " + exampleSql);
        }

        System.out.println("[SQL Sentry] \u4f4d\u7f6e: " + buildLocation(
                printContext == null ? null : printContext.getMappedStatementId()));
    }

    private String resolveStage(String diagnosisStatus) {
        if ("ai_ready".equalsIgnoreCase(diagnosisStatus)) {
            return "\u0041\u0049 \u6539\u5199\u65b9\u6848\u5df2\u751f\u6210";
        }
        if ("ai_pending".equalsIgnoreCase(diagnosisStatus)) {
            return "\u89c4\u5219\u8bca\u65ad\u5df2\u5b8c\u6210\uff0cAI \u7ed3\u679c\u5f85\u8fd4\u56de";
        }
        return "\u89c4\u5219\u8bca\u65ad\u5df2\u5b8c\u6210";
    }

    private String resolveHeadline(String diagnosisStatus, String rawHeadline, JsonNode root) {
        if ("ai_ready".equalsIgnoreCase(diagnosisStatus)) {
            return "\u8fd9\u6761 SQL \u5df2\u751f\u6210\u53ef\u590d\u7528\u7684\u4f18\u5316\u65b9\u6848\u3002";
        }

        String issue = extractIssue(rawHeadline);
        String risk = extractRisk(rawHeadline);
        if (hasText(issue) && hasText(risk)) {
            return "\u8fd9\u6761 SQL \u5b58\u5728" + readableIssue(issue) + "\uff0c\u5f53\u524d\u98ce\u9669\u7b49\u7ea7\u4e3a" + readableRisk(risk) + "\u3002";
        }

        String summary = textValue(root.path("summary"));
        if (hasText(summary)) {
            return summary;
        }
        return rawHeadline;
    }

    private String resolveSummary(String diagnosisStatus, String rawHeadline, String rawSummary) {
        if ("ai_ready".equalsIgnoreCase(diagnosisStatus)) {
            return hasText(rawSummary) ? rawSummary : "\u0041\u0049 \u5df2\u8fd4\u56de\u4e00\u7248\u53ef\u6267\u884c\u7684 SQL \u4f18\u5316\u5efa\u8bae\u3002";
        }

        String issue = extractIssue(rawHeadline);
        if ("ai_pending".equalsIgnoreCase(diagnosisStatus) && hasText(issue)) {
            return "\u5df2\u8bc6\u522b\u51fa" + readableIssue(issue) + "\uff0c\u670d\u52a1\u7aef\u6b63\u5728\u7ee7\u7eed\u751f\u6210 AI \u8bca\u65ad\u7ed3\u679c\u3002";
        }
        return rawSummary;
    }

    private String resolveAdvice(String rawHeadline, String rawAdvice) {
        if (!hasText(rawAdvice)) {
            return null;
        }

        String issue = extractIssue(rawHeadline);
        if ("\u6392\u5e8f\u538b\u529b".equals(issue)) {
            return "\u4f18\u5148\u68c0\u67e5 ORDER BY \u5b57\u6bb5\u662f\u5426\u548c\u7b5b\u9009\u6761\u4ef6\u5171\u7528\u540c\u4e00\u6761\u53ef\u547d\u4e2d\u7684\u7d22\u5f15\u3002";
        }
        if ("LIKE \u524d\u5bfc\u901a\u914d".equals(issue)) {
            return "\u907f\u514d\u4f7f\u7528\u524d\u5bfc\u6a21\u7cca\u5339\u914d\uff0c\u4f18\u5148\u6539\u6210\u524d\u7f00\u5339\u914d\u6216\u5168\u6587\u68c0\u7d22\u3002";
        }
        if ("\u5168\u8868\u626b\u63cf".equals(issue)) {
            return "\u4f18\u5148\u68c0\u67e5\u8fc7\u6ee4\u6761\u4ef6\u548c\u7d22\u5f15\u547d\u4e2d\u60c5\u51b5\uff0c\u907f\u514d\u6574\u8868\u626b\u63cf\u3002";
        }
        if ("\u67e5\u8be2\u4e86\u5168\u90e8\u5217".equals(issue)) {
            return "\u4e0d\u8981\u518d\u7528 SELECT *\uff0c\u53ea\u4fdd\u7559\u771f\u6b63\u9700\u8981\u8fd4\u56de\u7684\u5b57\u6bb5\u3002";
        }
        return rawAdvice;
    }

    private String extractRisk(String headline) {
        if (!hasText(headline)) {
            return null;
        }
        int delimiter = indexOfDelimiter(headline);
        if (delimiter <= 0) {
            return null;
        }
        return headline.substring(0, delimiter).trim();
    }

    private String extractIssue(String headline) {
        if (!hasText(headline)) {
            return null;
        }
        int delimiter = indexOfDelimiter(headline);
        if (delimiter < 0 || delimiter == headline.length() - 1) {
            return headline.trim();
        }
        return headline.substring(delimiter + 1).trim();
    }

    private String readableRisk(String risk) {
        if ("\u9ad8".equals(risk) || "\u4e2d".equals(risk) || "\u4f4e".equals(risk) || "\u4e25\u91cd".equals(risk)) {
            return risk;
        }
        return risk;
    }

    private String readableIssue(String issue) {
        if ("\u6392\u5e8f\u538b\u529b".equals(issue)) {
            return "\u6392\u5e8f\u5f00\u9500";
        }
        if ("LIKE \u524d\u5bfc\u901a\u914d".equals(issue)) {
            return "\u524d\u5bfc\u6a21\u7cca\u5339\u914d\u5bfc\u81f4\u7684\u7d22\u5f15\u5931\u6548";
        }
        if ("\u5168\u8868\u626b\u63cf".equals(issue)) {
            return "\u5168\u8868\u626b\u63cf";
        }
        if ("\u67e5\u8be2\u4e86\u5168\u90e8\u5217".equals(issue)) {
            return "\u8fd4\u56de\u5217\u8fc7\u591a";
        }
        return issue;
    }

    private int indexOfDelimiter(String headline) {
        int delimiter = headline.indexOf('\uFF1A');
        if (delimiter >= 0) {
            return delimiter;
        }
        return headline.indexOf(':');
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return hasText(value) ? value.trim() : null;
    }

    private String buildLocation(String mappedStatementId) {
        return hasText(mappedStatementId) ? mappedStatementId.trim() : "unknown.mapper.method";
    }

    private String buildConclusion(SqlClientDiagnosis diagnosis) {
        return firstNonBlank(diagnosis.getHeadline(), diagnosis.getSummary(), diagnosis.getAdvice(), "\u89c4\u5219\u8bca\u65ad\u5df2\u5b8c\u6210\u3002");
    }

    private String buildCopyableExampleSql(SqlClientDiagnosis diagnosis, SqlCapturePrintContext printContext) {
        String exampleSql = firstNonBlank(
                printContext == null ? null : printContext.getExampleSql(),
                diagnosis.getOptimizedSqlPreview());
        return compactSql(exampleSql);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String compactSql(String sql) {
        if (!hasText(sql)) {
            return null;
        }
        return sql.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
