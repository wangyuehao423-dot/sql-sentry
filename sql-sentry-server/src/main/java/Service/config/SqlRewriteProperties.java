package Service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sql-rewrite")
public class SqlRewriteProperties {

    private long cacheTtlHours = 24L;
    private int recentListLimit = 512;
    private int exportLimit = 512;

    public long getCacheTtlHours() {
        return cacheTtlHours;
    }

    public void setCacheTtlHours(long cacheTtlHours) {
        this.cacheTtlHours = cacheTtlHours;
    }

    public int getRecentListLimit() {
        return recentListLimit;
    }

    public void setRecentListLimit(int recentListLimit) {
        this.recentListLimit = recentListLimit;
    }

    public int getExportLimit() {
        return exportLimit;
    }

    public void setExportLimit(int exportLimit) {
        this.exportLimit = exportLimit;
    }
}
