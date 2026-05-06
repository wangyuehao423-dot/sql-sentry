package Service.concurrent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MonitoringThreadPoolExecutor extends ThreadPoolExecutor {

    private final String poolName;
    private final ThreadPoolListener listener;

    public MonitoringThreadPoolExecutor(
            String poolName,
            int corePoolSize,
            int maximumPoolSize,
            long keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue,
            ThreadFactory threadFactory,
            RejectedExecutionHandler handler,
            ThreadPoolListener listener) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
        this.poolName = poolName;
        this.listener = listener;
    }

    public void executeTracked(String taskName, Runnable command) {
        super.execute(new TimedRunnable(taskName, command));
    }

    @Override
    protected void beforeExecute(Thread thread, Runnable runnable) {
        super.beforeExecute(thread, runnable);
        if (listener == null || !(runnable instanceof TimedRunnable)) {
            return;
        }

        TimedRunnable tracked = (TimedRunnable) runnable;
        tracked.markStarted();
        listener.onTaskStarted(poolName, tracked.getTaskName(), tracked.queueWaitNanos(), getActiveCount(), getQueue().size());
    }

    @Override
    protected void afterExecute(Runnable runnable, Throwable throwable) {
        try {
            if (listener != null && runnable instanceof TimedRunnable) {
                TimedRunnable tracked = (TimedRunnable) runnable;
                listener.onTaskCompleted(poolName, tracked.getTaskName(), tracked.executionNanos(), throwable, getActiveCount(), getQueue().size());
            }
        } finally {
            super.afterExecute(runnable, throwable);
        }
    }

    public String getPoolName() {
        return poolName;
    }

    public static String taskNameOf(Runnable runnable) {
        if (runnable instanceof TimedRunnable) {
            return ((TimedRunnable) runnable).getTaskName();
        }
        return "anonymous-task";
    }

    private static final class TimedRunnable implements Runnable {
        private final String taskName;
        private final Runnable delegate;
        private final long queuedAtNanos = System.nanoTime();
        private volatile long startedAtNanos;

        private TimedRunnable(String taskName, Runnable delegate) {
            this.taskName = taskName;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            delegate.run();
        }

        private void markStarted() {
            startedAtNanos = System.nanoTime();
        }

        private long queueWaitNanos() {
            long start = startedAtNanos;
            return start <= 0L ? 0L : start - queuedAtNanos;
        }

        private long executionNanos() {
            long start = startedAtNanos;
            return start <= 0L ? 0L : System.nanoTime() - start;
        }

        private String getTaskName() {
            return taskName;
        }
    }
}
