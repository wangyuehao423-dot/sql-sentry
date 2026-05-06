package Service.concurrent;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SingleFlightCoordinator {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public SingleFlightCoordinator(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<FlightResult<T>> execute(
            String key,
            String taskName,
            long timeoutMillis,
            Callable<T> task,
            Executor executor) {
        CompletableFuture<Object> leaderFuture = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, leaderFuture);
        if (existing != null) {
            return awaitShared(existing);
        }

        ScheduledFuture<?> timeoutFuture = scheduler.schedule(() -> {
            if (leaderFuture.completeExceptionally(new TimeoutException(taskName + " timed out after " + timeoutMillis + " ms"))) {
                inFlight.remove(key, leaderFuture);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);

        leaderFuture.whenComplete((value, error) -> {
            timeoutFuture.cancel(false);
            inFlight.remove(key, leaderFuture);
        });

        try {
            executor.execute(() -> {
                try {
                    leaderFuture.complete(task.call());
                } catch (Throwable throwable) {
                    leaderFuture.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException e) {
            leaderFuture.completeExceptionally(e);
            inFlight.remove(key, leaderFuture);
        }

        return leaderFuture.thenApply(value -> new FlightResult<T>((T) value, false));
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<FlightResult<T>> awaitShared(CompletableFuture<Object> existing) {
        CompletableFuture<FlightResult<T>> sharedFuture = new CompletableFuture<>();
        existing.whenComplete((value, error) -> {
            if (error != null) {
                sharedFuture.completeExceptionally(error);
                return;
            }
            try {
                sharedFuture.complete(new FlightResult<T>((T) existing.join(), true));
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                sharedFuture.completeExceptionally(cause);
            }
        });
        return sharedFuture;
    }

    public void shutdown() {
        for (Map.Entry<String, CompletableFuture<Object>> entry : inFlight.entrySet()) {
            entry.getValue().completeExceptionally(new IllegalStateException("Single-flight coordinator is shutting down"));
        }
        inFlight.clear();
    }

    public static final class FlightResult<T> {
        private final T value;
        private final boolean shared;

        private FlightResult(T value, boolean shared) {
            this.value = value;
            this.shared = shared;
        }

        public T getValue() {
            return value;
        }

        public boolean isShared() {
            return shared;
        }
    }
}
