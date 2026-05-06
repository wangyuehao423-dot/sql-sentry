package Service.concurrent;

public interface ThreadPoolListener {

    void onTaskStarted(String poolName, String taskName, long queueWaitNanos, int activeCount, int queueSize);

    void onTaskCompleted(String poolName, String taskName, long executionNanos, Throwable error, int activeCount, int queueSize);

    void onTaskRejected(String poolName, String taskName, int queueSize);
}
