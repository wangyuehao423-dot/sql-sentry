package com.yuehao.sqlsentry.model;

public class SqlClientDiagnosis {

    private final String fingerprint;
    private final String stage;
    private final String status;
    private final String headline;
    private final String summary;
    private final String advice;
    private final String optimizedSqlPreview;

    public SqlClientDiagnosis(
            String fingerprint,
            String stage,
            String status,
            String headline,
            String summary,
            String advice,
            String optimizedSqlPreview) {
        this.fingerprint = fingerprint;
        this.stage = stage;
        this.status = status;
        this.headline = headline;
        this.summary = summary;
        this.advice = advice;
        this.optimizedSqlPreview = optimizedSqlPreview;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getStage() {
        return stage;
    }

    public String getStatus() {
        return status;
    }

    public String getHeadline() {
        return headline;
    }

    public String getSummary() {
        return summary;
    }

    public String getAdvice() {
        return advice;
    }

    public String getOptimizedSqlPreview() {
        return optimizedSqlPreview;
    }
}
