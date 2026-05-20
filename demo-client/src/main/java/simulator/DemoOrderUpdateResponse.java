package simulator;

public class DemoOrderUpdateResponse {

    private final int affectedRows;
    private final DemoSqlSentryView sqlSentry;

    public DemoOrderUpdateResponse(int affectedRows, DemoSqlSentryView sqlSentry) {
        this.affectedRows = affectedRows;
        this.sqlSentry = sqlSentry;
    }

    public int getAffectedRows() {
        return affectedRows;
    }

    public DemoSqlSentryView getSqlSentry() {
        return sqlSentry;
    }
}
