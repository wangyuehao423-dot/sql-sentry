package com.yuehao.sqlsentry.model;

public class SqlRewriteRule {

    private final String fingerprint;
    private final String optimizedSql;
    private final String updatedAt;
    private final String summary;
    private final String advice;
    private final String rewriteStatus;
    private final String exampleSql;

    public SqlRewriteRule(
            String fingerprint,
            String optimizedSql,
            String updatedAt,
            String summary,
            String advice,
            String rewriteStatus,
            String exampleSql) {
        this.fingerprint = fingerprint;
        this.optimizedSql = optimizedSql;
        this.updatedAt = updatedAt;
        this.summary = summary;
        this.advice = advice;
        this.rewriteStatus = rewriteStatus;
        this.exampleSql = exampleSql;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getOptimizedSql() {
        return optimizedSql;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getSummary() {
        return summary;
    }

    public String getAdvice() {
        return advice;
    }

    public String getRewriteStatus() {
        return rewriteStatus;
    }

    public String getExampleSql() {
        return exampleSql;
    }
}
