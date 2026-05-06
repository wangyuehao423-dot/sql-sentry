package Service.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证同一个 key 的请求会共享同一次 leader 执行。
 */
class SingleFlightCoordinatorTest {

    @Test
    void shouldOnlyExecuteLeaderOnceForSameKey() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        SingleFlightCoordinator coordinator = new SingleFlightCoordinator(scheduler);
        AtomicInteger invocationCount = new AtomicInteger();
        CountDownLatch releaseLeader = new CountDownLatch(1);

        try {
            CompletableFuture<SingleFlightCoordinator.FlightResult<String>> first = coordinator.execute(
                    "same-key",
                    "test-task",
                    5000L,
                    () -> {
                        invocationCount.incrementAndGet();
                        releaseLeader.await();
                        return "rewritten-sql";
                    },
                    executor
            );

            CompletableFuture<SingleFlightCoordinator.FlightResult<String>> second = coordinator.execute(
                    "same-key",
                    "test-task",
                    5000L,
                    () -> {
                        invocationCount.incrementAndGet();
                        return "should-not-run";
                    },
                    executor
            );

            releaseLeader.countDown();

            SingleFlightCoordinator.FlightResult<String> firstResult = first.join();
            SingleFlightCoordinator.FlightResult<String> secondResult = second.join();

            assertEquals(1, invocationCount.get());
            assertFalse(firstResult.isShared());
            assertTrue(secondResult.isShared());
            assertEquals("rewritten-sql", firstResult.getValue());
            assertEquals("rewritten-sql", secondResult.getValue());
        } finally {
            coordinator.shutdown();
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }
}
