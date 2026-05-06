package com.yuehao.sqlsentry.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import com.yuehao.sqlsentry.model.SqlRewriteRule;
import com.yuehao.sqlsentry.rewrite.SqlRewriteLocalCache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.Iterator;

public class SqlSentryPullClient {

    private static final Logger log = LoggerFactory.getLogger(SqlSentryPullClient.class);

    private final SqlSentryProperties properties;
    private final SqlRewriteLocalCache localCache;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SqlSentryPullClient(
            SqlSentryProperties properties,
            SqlRewriteLocalCache localCache,
            OkHttpClient okHttpClient) {
        this.properties = properties;
        this.localCache = localCache;
        this.okHttpClient = okHttpClient;
    }

    @PostConstruct
    public void initialPull() {
        pullLatestMappings();
    }

    @Scheduled(fixedDelayString = "${sql.sentry.pull-interval-ms:30000}")
    public void pullLatestMappings() {
        if (!properties.isEnabled() || !properties.isRewriteEnabled() || !hasText(properties.getServerBaseUrl())) {
            return;
        }

        Request request = new Request.Builder()
                .url(buildMappingsUrl())
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return;
            }

            JsonNode root = objectMapper.readTree(response.body().string());
            JsonNode mappings = root.path("mappings");
            if (!mappings.isArray()) {
                return;
            }

            Iterator<JsonNode> iterator = mappings.elements();
            while (iterator.hasNext()) {
                JsonNode item = iterator.next();
                String fingerprint = textValue(item.path("fingerprint"));
                String optimizedSql = textValue(item.path("optimizedSql"));
                String rewriteStatus = textValue(item.path("rewriteStatus"));
                if (!"approved".equalsIgnoreCase(rewriteStatus) || !hasText(fingerprint) || !hasText(optimizedSql)) {
                    continue;
                }

                localCache.put(new SqlRewriteRule(
                        fingerprint,
                        optimizedSql,
                        textValue(item.path("updatedAt")),
                        textValue(item.path("summary")),
                        textValue(item.path("advice")),
                        rewriteStatus,
                        textValue(item.path("originalSql"))));
            }
        } catch (IOException e) {
            log.debug("Failed to pull rewrite mappings from server: {}", e.toString());
        }
    }

    private String buildMappingsUrl() {
        String baseUrl = properties.getServerBaseUrl().trim();
        String path = hasText(properties.getMappingsPath()) ? properties.getMappingsPath().trim() : "/api/sql/rewrite-mappings";
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        } else if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path + "?limit=" + Math.max(1, properties.getMaxLocalMappings());
        }
        return baseUrl + path + "?limit=" + Math.max(1, properties.getMaxLocalMappings());
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
