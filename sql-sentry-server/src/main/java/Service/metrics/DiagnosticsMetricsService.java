package Service.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Service
public class DiagnosticsMetricsService {

    private final LongAdder diagnosticRequests = new LongAdder();
    private final LongAdder rateLimitedRequests = new LongAdder();
    private final LongAdder aiTimeouts = new LongAdder();
    private final LongAdder aiFailures = new LongAdder();
    private final LongAdder redisFallbacks = new LongAdder();
    private final LongAdder singleFlightSharedHits = new LongAdder();
    private final LongAdder cacheRedisHits = new LongAdder();
    private final LongAdder cacheLocalHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder rewriteMappingsPublished = new LongAdder();
    private final LongAdder rewriteMappingsRejected = new LongAdder();
    private final LongAdder ruleEngineMicros = new LongAdder();
    private final LongAdder ruleEngineSamples = new LongAdder();
    private final LongAdder llmMicros = new LongAdder();
    private final LongAdder llmSamples = new LongAdder();
    private final LongAdder totalMicros = new LongAdder();
    private final LongAdder totalSamples = new LongAdder();
    private final Map<String, LongAdder> poolQueueMicros = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> poolQueueSamples = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> poolExecutionMicros = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> poolExecutionSamples = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> poolRejections = new ConcurrentHashMap<>();

    public void incrementDiagnosticRequests() {
        diagnosticRequests.increment();
    }

    public void incrementRateLimitedRequests() {
        rateLimitedRequests.increment();
    }

    public void incrementAiTimeouts() {
        aiTimeouts.increment();
    }

    public void incrementAiFailures() {
        aiFailures.increment();
    }

    public void incrementRedisFallbacks() {
        redisFallbacks.increment();
    }

    public void incrementSingleFlightSharedHits() {
        singleFlightSharedHits.increment();
    }

    public void recordCacheHit(String source) {
        if ("redis-cache".equalsIgnoreCase(source)) {
            cacheRedisHits.increment();
            return;
        }
        if ("local-cache".equalsIgnoreCase(source)) {
            cacheLocalHits.increment();
        }
    }

    public void incrementCacheMisses() {
        cacheMisses.increment();
    }

    public void incrementRewriteMappingsPublished() {
        rewriteMappingsPublished.increment();
    }

    public void incrementRewriteMappingsRejected() {
        rewriteMappingsRejected.increment();
    }

    public void recordRuleEngineDuration(long nanos) {
        recordDuration(nanos, ruleEngineMicros, ruleEngineSamples);
    }

    public void recordLlmDuration(long nanos) {
        recordDuration(nanos, llmMicros, llmSamples);
    }

    public void recordTotalDuration(long nanos) {
        recordDuration(nanos, totalMicros, totalSamples);
    }

    public void recordPoolQueueWait(String poolName, long nanos) {
        recordDuration(nanos, counter(poolQueueMicros, poolName), counter(poolQueueSamples, poolName));
    }

    public void recordPoolExecution(String poolName, long nanos) {
        recordDuration(nanos, counter(poolExecutionMicros, poolName), counter(poolExecutionSamples, poolName));
    }

    public void incrementPoolRejections(String poolName) {
        counter(poolRejections, poolName).increment();
    }

    public ObjectNode snapshot(ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("diagnosticRequests", diagnosticRequests.sum());
        root.put("rateLimitedRequests", rateLimitedRequests.sum());
        root.put("aiTimeouts", aiTimeouts.sum());
        root.put("aiFailures", aiFailures.sum());
        root.put("redisFallbacks", redisFallbacks.sum());
        root.put("singleFlightSharedHits", singleFlightSharedHits.sum());
        root.put("cacheRedisHits", cacheRedisHits.sum());
        root.put("cacheLocalHits", cacheLocalHits.sum());
        root.put("cacheMisses", cacheMisses.sum());
        root.put("rewriteMappingsPublished", rewriteMappingsPublished.sum());
        root.put("rewriteMappingsRejected", rewriteMappingsRejected.sum());
        root.put("avgRuleEngineMicros", average(ruleEngineMicros, ruleEngineSamples));
        root.put("avgLlmMicros", average(llmMicros, llmSamples));
        root.put("avgTotalMicros", average(totalMicros, totalSamples));

        ObjectNode pools = root.putObject("threadPools");
        for (Map.Entry<String, LongAdder> entry : poolExecutionSamples.entrySet()) {
            String poolName = entry.getKey();
            ObjectNode poolNode = pools.putObject(poolName);
            poolNode.put("avgQueueWaitMicros", average(counter(poolQueueMicros, poolName), counter(poolQueueSamples, poolName)));
            poolNode.put("avgExecutionMicros", average(counter(poolExecutionMicros, poolName), counter(poolExecutionSamples, poolName)));
            poolNode.put("rejections", counter(poolRejections, poolName).sum());
        }
        return root;
    }

    private void recordDuration(long nanos, LongAdder total, LongAdder samples) {
        long micros = nanos <= 0L ? 0L : nanos / 1000L;
        total.add(micros);
        samples.increment();
    }

    private long average(LongAdder total, LongAdder samples) {
        long sampleCount = samples.sum();
        if (sampleCount == 0L) {
            return 0L;
        }
        return total.sum() / sampleCount;
    }

    private LongAdder counter(Map<String, LongAdder> counters, String key) {
        LongAdder existing = counters.get(key);
        if (existing != null) {
            return existing;
        }
        LongAdder created = new LongAdder();
        LongAdder raced = counters.putIfAbsent(key, created);
        return raced == null ? created : raced;
    }
}
