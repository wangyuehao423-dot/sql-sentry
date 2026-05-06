package Service.rewrite;

import Service.SqlHeuristicAnalyzer;
import Service.cache.CachedJsonValue;
import Service.cache.JsonCacheStore;
import Service.config.SqlRewriteProperties;
import Service.metrics.DiagnosticsMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class SqlRewriteMappingService {

    private static final Logger log = LoggerFactory.getLogger(SqlRewriteMappingService.class);
    private static final String REWRITE_MAPPING_KEY_PREFIX = "sql_rewrite:mapping:";
    private static final String RECENT_REWRITE_LIST_KEY = "sql_rewrite:mappings:recent";

    private final JsonCacheStore cacheStore;
    private final SqlRewriteProperties properties;
    private final DiagnosticsMetricsService metricsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SqlRewriteMappingService(
            JsonCacheStore cacheStore,
            SqlRewriteProperties properties,
            DiagnosticsMetricsService metricsService) {
        this.cacheStore = cacheStore;
        this.properties = properties;
        this.metricsService = metricsService;
    }

    public String fingerprint(String sql) {
        return SqlFingerprintUtils.fingerprint(sql);
    }

    public CachedJsonValue findByFingerprint(String fingerprint) {
        CachedJsonValue cachedValue = cacheStore.getValue(buildMappingKey(fingerprint));
        if (cachedValue == null || cachedValue.getPayload() == null) {
            return null;
        }
        String rewriteStatus = cachedValue.getPayload().path("rewriteStatus").asText("");
        return "approved".equals(rewriteStatus) ? cachedValue : null;
    }

    public void storeApprovedRewrite(
            String fingerprint,
            SqlHeuristicAnalyzer.AnalysisSnapshot analysis,
            String optimizedSql,
            String summary,
            String advice) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("fingerprint", fingerprint);
        payload.put("originalSql", SqlFingerprintUtils.normalize(analysis.getNormalizedSql()));
        payload.put("optimizedSql", SqlFingerprintUtils.normalize(optimizedSql));
        payload.put("summary", summary);
        payload.put("advice", advice);
        payload.put("riskLevel", analysis.getRiskLevel());
        payload.put("riskScore", analysis.getRiskScore());
        payload.put("updatedAt", Instant.now().toString());
        payload.put("rewriteStatus", "approved");
        payload.put("source", "ai");
        persistMapping(fingerprint, payload);
    }

    public ObjectNode exportMappings(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, Math.max(1, properties.getExportLimit())));
        int fetchSize = Math.min(
                Math.max(safeLimit * 2, safeLimit),
                Math.max(32, properties.getRecentListLimit()));

        JsonNode items = cacheStore.range(RECENT_REWRITE_LIST_KEY, fetchSize);
        Map<String, JsonNode> deduplicated = new LinkedHashMap<String, JsonNode>();
        if (items != null && items.isArray()) {
            Iterator<JsonNode> iterator = items.elements();
            while (iterator.hasNext() && deduplicated.size() < safeLimit) {
                JsonNode item = iterator.next();
                if (!"approved".equals(item.path("rewriteStatus").asText(""))) {
                    continue;
                }
                String fingerprint = item.path("fingerprint").asText("");
                if (!fingerprint.isEmpty() && !deduplicated.containsKey(fingerprint)) {
                    deduplicated.put(fingerprint, item);
                }
            }
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("count", deduplicated.size());
        ArrayNode mappings = payload.putArray("mappings");
        for (JsonNode value : deduplicated.values()) {
            mappings.add(value);
        }
        return payload;
    }

    private void persistMapping(String fingerprint, ObjectNode payload) {
        try {
            cacheStore.putValue(
                    buildMappingKey(fingerprint),
                    payload,
                    Math.max(1L, properties.getCacheTtlHours()),
                    TimeUnit.HOURS);
            cacheStore.pushLeft(
                    RECENT_REWRITE_LIST_KEY,
                    payload,
                    Math.max(32, properties.getRecentListLimit()));
            metricsService.incrementRewriteMappingsPublished();
        } catch (IOException e) {
            log.warn("Failed to persist rewrite mapping for fingerprint {}: {}", fingerprint, e.toString());
        }
    }

    private String buildMappingKey(String fingerprint) {
        return REWRITE_MAPPING_KEY_PREFIX + fingerprint;
    }
}
