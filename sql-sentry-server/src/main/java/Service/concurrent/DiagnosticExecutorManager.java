package Service.concurrent;

import Service.config.DiagnosticThreadPoolProperties;
import Service.metrics.DiagnosticsMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class DiagnosticExecutorManager {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticExecutorManager.class);

    private final MonitoringThreadPoolExecutor ruleExecutor;
    private final MonitoringThreadPoolExecutor llmExecutor;
    private final ScheduledExecutorService timeoutScheduler;

    public DiagnosticExecutorManager(
            DiagnosticThreadPoolProperties properties,
            DiagnosticsMetricsService metricsService) {
        ThreadPoolListener listener = new ThreadPoolListener() {
            @Override
            public void onTaskStarted(String poolName, String taskName, long queueWaitNanos, int activeCount, int queueSize) {
                metricsService.recordPoolQueueWait(poolName, queueWaitNanos);
            }

            @Override
            public void onTaskCompleted(String poolName, String taskName, long executionNanos, Throwable error, int activeCount, int queueSize) {
                metricsService.recordPoolExecution(poolName, executionNanos);
            }

            @Override
            public void onTaskRejected(String poolName, String taskName, int queueSize) {
                metricsService.incrementPoolRejections(poolName);
                log.warn("Thread pool {} rejected task {}, queueSize={}", poolName, taskName, queueSize);
            }
        };

        this.ruleExecutor = createExecutor("rule", properties.getRule(), listener);
        this.llmExecutor = createExecutor("llm", properties.getLlm(), listener);
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("diag-timeout-", true));
    }

    public <T> CompletableFuture<T> submitRule(String taskName, Supplier<T> supplier) {
        return submit(ruleExecutor, taskName, supplier);
    }

    public <T> CompletableFuture<T> submitLlm(String taskName, Supplier<T> supplier) {
        return submit(llmExecutor, taskName, supplier);
    }

    public Executor llmExecutor() {
        return llmExecutor;
    }

    public ScheduledExecutorService timeoutScheduler() {
        return timeoutScheduler;
    }

    public ObjectNode snapshot(ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("rule", poolSnapshot(objectMapper, ruleExecutor));
        root.set("llm", poolSnapshot(objectMapper, llmExecutor));
        return root;
    }

    @PreDestroy
    public void shutdown() {
        ruleExecutor.shutdown();
        llmExecutor.shutdown();
        timeoutScheduler.shutdown();
    }

    private MonitoringThreadPoolExecutor createExecutor(
            String poolName,
            DiagnosticThreadPoolProperties.PoolSpec poolSpec,
            ThreadPoolListener listener) {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int defaultCore = "llm".equals(poolName)
                ? Math.max(8, availableProcessors * 4)
                : Math.max(2, availableProcessors);
        int defaultMax = "llm".equals(poolName)
                ? Math.max(defaultCore, availableProcessors * 8)
                : Math.max(defaultCore, availableProcessors * 2);
        int defaultQueue = "llm".equals(poolName) ? 256 : 128;

        int coreSize = poolSpec.getCoreSize() > 0 ? poolSpec.getCoreSize() : defaultCore;
        int maxSize = poolSpec.getMaxSize() > 0 ? poolSpec.getMaxSize() : defaultMax;
        int queueCapacity = poolSpec.getQueueCapacity() > 0 ? poolSpec.getQueueCapacity() : defaultQueue;
        long keepAliveSeconds = poolSpec.getKeepAliveSeconds() > 0L ? poolSpec.getKeepAliveSeconds() : 60L;

        return new MonitoringThreadPoolExecutor(
                poolName,
                coreSize,
                Math.max(coreSize, maxSize),
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity),
                new NamedThreadFactory("diag-" + poolName + "-", true),
                rejectionHandler(poolName, listener),
                listener
        );
    }

    private RejectedExecutionHandler rejectionHandler(final String poolName, final ThreadPoolListener listener) {
        return (runnable, executor) -> {
            listener.onTaskRejected(poolName, MonitoringThreadPoolExecutor.taskNameOf(runnable), executor.getQueue().size());
            throw new RejectedExecutionException("Diagnostic " + poolName + " pool is overloaded");
        };
    }

    private <T> CompletableFuture<T> submit(
            MonitoringThreadPoolExecutor executor,
            String taskName,
            Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.executeTracked(taskName, () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private ObjectNode poolSnapshot(ObjectMapper objectMapper, MonitoringThreadPoolExecutor executor) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("poolName", executor.getPoolName());
        node.put("activeCount", executor.getActiveCount());
        node.put("poolSize", executor.getPoolSize());
        node.put("corePoolSize", executor.getCorePoolSize());
        node.put("maximumPoolSize", executor.getMaximumPoolSize());
        node.put("largestPoolSize", executor.getLargestPoolSize());
        node.put("queueSize", executor.getQueue().size());
        node.put("completedTaskCount", executor.getCompletedTaskCount());
        return node;
    }
}
