package Service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.thread-pool")
public class DiagnosticThreadPoolProperties {

    private final PoolSpec rule = new PoolSpec();
    private final PoolSpec llm = new PoolSpec();

    public PoolSpec getRule() {
        return rule;
    }

    public PoolSpec getLlm() {
        return llm;
    }

    public static class PoolSpec {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        private long keepAliveSeconds = 60L;

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(long keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }
    }
}
