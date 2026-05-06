package com.yuehao.sqlsentry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sql.sentry")
public class SqlSentryProperties {

    private boolean enabled = false;
    private boolean rewriteEnabled = true;
    private boolean captureEnabled = true;
    private final Ai ai = new Ai();
    private String serverBaseUrl = "";
    private String capturePath = "/api/sql/captures";
    private String mappingsPath = "/api/sql/rewrite-mappings";
    private String source = "default-service";
    private String database = "default";
    private long pullIntervalMs = 30000L;
    private long slowSqlThresholdMs = 500L;
    private long connectTimeoutMs = 3000L;
    private long readTimeoutMs = 5000L;
    private long writeTimeoutMs = 5000L;
    private long callTimeoutMs = 10000L;
    private int maxLocalMappings = 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRewriteEnabled() {
        return rewriteEnabled;
    }

    public void setRewriteEnabled(boolean rewriteEnabled) {
        this.rewriteEnabled = rewriteEnabled;
    }

    public boolean isCaptureEnabled() {
        return captureEnabled;
    }

    public void setCaptureEnabled(boolean captureEnabled) {
        this.captureEnabled = captureEnabled;
    }

    public Ai getAi() {
        return ai;
    }

    public String getServerBaseUrl() {
        return serverBaseUrl;
    }

    public void setServerBaseUrl(String serverBaseUrl) {
        this.serverBaseUrl = serverBaseUrl;
    }

    public String getCapturePath() {
        return capturePath;
    }

    public void setCapturePath(String capturePath) {
        this.capturePath = capturePath;
    }

    public String getMappingsPath() {
        return mappingsPath;
    }

    public void setMappingsPath(String mappingsPath) {
        this.mappingsPath = mappingsPath;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public long getPullIntervalMs() {
        return pullIntervalMs;
    }

    public void setPullIntervalMs(long pullIntervalMs) {
        this.pullIntervalMs = pullIntervalMs;
    }

    public long getSlowSqlThresholdMs() {
        return slowSqlThresholdMs;
    }

    public void setSlowSqlThresholdMs(long slowSqlThresholdMs) {
        this.slowSqlThresholdMs = slowSqlThresholdMs;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public long getWriteTimeoutMs() {
        return writeTimeoutMs;
    }

    public void setWriteTimeoutMs(long writeTimeoutMs) {
        this.writeTimeoutMs = writeTimeoutMs;
    }

    public long getCallTimeoutMs() {
        return callTimeoutMs;
    }

    public void setCallTimeoutMs(long callTimeoutMs) {
        this.callTimeoutMs = callTimeoutMs;
    }

    public int getMaxLocalMappings() {
        return maxLocalMappings;
    }

    public void setMaxLocalMappings(int maxLocalMappings) {
        this.maxLocalMappings = maxLocalMappings;
    }

    public static class Ai {
        private String model = "";
        private String apiUrl = "";
        private String apiKey = "";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
