package Service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private long localTtlSeconds = 86400L;
    private long maxEntries = 2048L;
    private final RedisBreaker redis = new RedisBreaker();

    public long getLocalTtlSeconds() {
        return localTtlSeconds;
    }

    public void setLocalTtlSeconds(long localTtlSeconds) {
        this.localTtlSeconds = localTtlSeconds;
    }

    public long getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(long maxEntries) {
        this.maxEntries = maxEntries;
    }

    public RedisBreaker getRedis() {
        return redis;
    }

    public static class RedisBreaker {
        private int failureThreshold = 3;
        private long openWindowMs = 30000L;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenWindowMs() {
            return openWindowMs;
        }

        public void setOpenWindowMs(long openWindowMs) {
            this.openWindowMs = openWindowMs;
        }
    }
}
