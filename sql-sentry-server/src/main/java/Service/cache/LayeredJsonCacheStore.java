package Service.cache;

import Service.config.CacheProperties;
import Service.metrics.DiagnosticsMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

@Service
public class LayeredJsonCacheStore implements JsonCacheStore {

    private static final Logger log = LoggerFactory.getLogger(LayeredJsonCacheStore.class);

    private final StringRedisTemplate redisTemplate;
    private final DiagnosticsMetricsService metricsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Cache<String, String> localValueCache;
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> localLists = new ConcurrentHashMap<>();
    private final RedisCircuitBreaker redisCircuitBreaker;

    public LayeredJsonCacheStore(
            StringRedisTemplate redisTemplate,
            CacheProperties cacheProperties,
            DiagnosticsMetricsService metricsService) {
        this.redisTemplate = redisTemplate;
        this.metricsService = metricsService;
        this.localValueCache = Caffeine.newBuilder()
                .maximumSize(Math.max(256L, cacheProperties.getMaxEntries()))
                .expireAfterWrite(Math.max(60L, cacheProperties.getLocalTtlSeconds()), TimeUnit.SECONDS)
                .build();
        this.redisCircuitBreaker = new RedisCircuitBreaker(cacheProperties.getRedis());
    }

    @Override
    public CachedJsonValue getValue(String key) {
        if (redisCircuitBreaker.allowRequest()) {
            try {
                String cachedJson = redisTemplate.opsForValue().get(key);
                redisCircuitBreaker.recordSuccess();
                if (hasText(cachedJson)) {
                    localValueCache.put(key, cachedJson);
                    metricsService.recordCacheHit("redis-cache");
                    return new CachedJsonValue((ObjectNode) objectMapper.readTree(cachedJson), "redis-cache");
                }
            } catch (Exception e) {
                onRedisFailure("getValue", e);
            }
        } else {
            metricsService.incrementRedisFallbacks();
        }

        String localJson = localValueCache.getIfPresent(key);
        if (!hasText(localJson)) {
            metricsService.incrementCacheMisses();
            return null;
        }

        try {
            metricsService.recordCacheHit("local-cache");
            return new CachedJsonValue((ObjectNode) objectMapper.readTree(localJson), "local-cache");
        } catch (IOException e) {
            localValueCache.invalidate(key);
            metricsService.incrementCacheMisses();
            return null;
        }
    }

    @Override
    public void putValue(String key, ObjectNode payload, long ttl, TimeUnit unit) throws IOException {
        String serialized = objectMapper.writeValueAsString(payload);
        localValueCache.put(key, serialized);

        if (!redisCircuitBreaker.allowRequest()) {
            metricsService.incrementRedisFallbacks();
            return;
        }

        try {
            redisTemplate.opsForValue().set(key, serialized, ttl, unit);
            redisCircuitBreaker.recordSuccess();
        } catch (Exception e) {
            onRedisFailure("putValue", e);
        }
    }

    @Override
    public JsonNode range(String key, int limit) {
        ArrayNode result = objectMapper.createArrayNode();
        int safeLimit = Math.max(1, limit);

        if (redisCircuitBreaker.allowRequest()) {
            try {
                List<String> items = redisTemplate.opsForList().range(key, 0, safeLimit - 1);
                redisCircuitBreaker.recordSuccess();
                if (items != null && !items.isEmpty()) {
                    for (String item : items) {
                        if (hasText(item)) {
                            result.add(objectMapper.readTree(item));
                        }
                    }
                    mirrorListToLocal(key, items, safeLimit);
                    return result;
                }
            } catch (Exception e) {
                onRedisFailure("range", e);
            }
        } else {
            metricsService.incrementRedisFallbacks();
        }

        ConcurrentLinkedDeque<String> localDeque = localLists.get(key);
        if (localDeque == null || localDeque.isEmpty()) {
            return result;
        }

        int count = 0;
        for (String item : localDeque) {
            if (count >= safeLimit) {
                break;
            }
            if (hasText(item)) {
                try {
                    result.add(objectMapper.readTree(item));
                    count++;
                } catch (IOException ignored) {
                }
            }
        }
        return result;
    }

    @Override
    public void pushLeft(String key, ObjectNode payload, int maxSize) throws IOException {
        String serialized = objectMapper.writeValueAsString(payload);
        pushToLocalList(key, serialized, maxSize);

        if (!redisCircuitBreaker.allowRequest()) {
            metricsService.incrementRedisFallbacks();
            return;
        }

        try {
            redisTemplate.opsForList().leftPush(key, serialized);
            redisTemplate.opsForList().trim(key, 0, maxSize - 1);
            redisCircuitBreaker.recordSuccess();
        } catch (Exception e) {
            onRedisFailure("pushLeft", e);
        }
    }

    private void pushToLocalList(String key, String value, int maxSize) {
        ConcurrentLinkedDeque<String> deque = localLists.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<String>());
        deque.addFirst(value);
        while (deque.size() > maxSize) {
            deque.pollLast();
        }
    }

    private void mirrorListToLocal(String key, List<String> items, int maxSize) {
        ConcurrentLinkedDeque<String> deque = localLists.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<String>());
        deque.clear();
        for (String item : items) {
            if (hasText(item)) {
                deque.addLast(item);
            }
        }
        while (deque.size() > maxSize) {
            deque.pollLast();
        }
    }

    private void onRedisFailure(String operation, Exception exception) {
        metricsService.incrementRedisFallbacks();
        redisCircuitBreaker.recordFailure(exception);
        log.warn("Redis {} failed, falling back to local cache: {}", operation, exception.toString());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
