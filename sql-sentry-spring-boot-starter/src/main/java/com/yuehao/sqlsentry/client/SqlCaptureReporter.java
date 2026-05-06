package com.yuehao.sqlsentry.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SqlCaptureReporter(SqlSentryProperties properties, OkHttpClient okHttpClient) {
        this.properties = properties;
        this.okHttpClient = okHttpClient;
    }

    public void reportSlowSql(String sql, long elapsedMs) {
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
                    printClientDiagnosis(root);
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

    private void printClientDiagnosis(JsonNode root) {
        String headline = textValue(root.path("headline"));
        String summary = textValue(root.path("summary"));
        String advice = textValue(root.path("advice"));

        if (hasText(headline)) {
            System.out.println("[SQL Sentry] " + headline);
        }
        if (hasText(summary)) {
            System.out.println("[SQL Sentry] " + summary);
        }
        if (hasText(advice)) {
            System.out.println("[SQL Sentry] Advice: " + advice);
        }
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
}
