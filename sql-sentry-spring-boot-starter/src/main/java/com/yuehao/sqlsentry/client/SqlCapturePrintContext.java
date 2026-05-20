package com.yuehao.sqlsentry.client;

public class SqlCapturePrintContext {

    private final String mappedStatementId;
    private final String exampleSql;
    private final boolean autoReplaced;
    private final String rewriteReason;

    public SqlCapturePrintContext(
            String mappedStatementId,
            String exampleSql,
            boolean autoReplaced,
            String rewriteReason) {
        this.mappedStatementId = mappedStatementId;
        this.exampleSql = exampleSql;
        this.autoReplaced = autoReplaced;
        this.rewriteReason = rewriteReason;
    }

    public String getMappedStatementId() {
        return mappedStatementId;
    }

    public String getExampleSql() {
        return exampleSql;
    }

    public boolean isAutoReplaced() {
        return autoReplaced;
    }

    public String getRewriteReason() {
        return rewriteReason;
    }
}
