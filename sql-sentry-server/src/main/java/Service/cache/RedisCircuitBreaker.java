package Service.cache;

import Service.config.CacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RedisCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreaker.class);

    private final int failureThreshold;
    private final long openWindowMs;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilMillis = new AtomicLong();
    private final AtomicBoolean halfOpenProbe = new AtomicBoolean();

    public RedisCircuitBreaker(CacheProperties.RedisBreaker properties) {
        this.failureThreshold = Math.max(1, properties.getFailureThreshold());
        this.openWindowMs = Math.max(1000L, properties.getOpenWindowMs());
    }

    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        long openUntil = openUntilMillis.get();
        if (openUntil == 0L) {
            return true;
        }
        if (now < openUntil) {
            return false;
        }
        return halfOpenProbe.compareAndSet(false, true);
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilMillis.set(0L);
        halfOpenProbe.set(false);
    }

    public void recordFailure(Exception exception) {
        int currentFailures = consecutiveFailures.incrementAndGet();
        if (halfOpenProbe.getAndSet(false) || currentFailures >= failureThreshold) {
            long nextWindow = System.currentTimeMillis() + openWindowMs;
            openUntilMillis.set(nextWindow);
            consecutiveFailures.set(0);
            log.warn("Redis circuit opened until {} because of {}", nextWindow, exception.toString());
        }
    }
}
