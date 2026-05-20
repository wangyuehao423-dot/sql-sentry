package simulator;

import java.util.List;

public class DemoOrderQueryResponse {

    private final List<DemoOrder> orders;
    private final DemoSqlSentryView sqlSentry;

    public DemoOrderQueryResponse(List<DemoOrder> orders, DemoSqlSentryView sqlSentry) {
        this.orders = orders;
        this.sqlSentry = sqlSentry;
    }

    public List<DemoOrder> getOrders() {
        return orders;
    }

    public DemoSqlSentryView getSqlSentry() {
        return sqlSentry;
    }
}
