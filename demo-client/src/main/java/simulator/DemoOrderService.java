package simulator;

import com.yuehao.sqlsentry.client.SqlSentryClientViewStore;
import com.yuehao.sqlsentry.rewrite.SqlFingerprintUtils;
import com.yuehao.sqlsentry.model.SqlClientDiagnosis;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DemoOrderService {

    private static final String SELECT_BY_STATUS_SQL = "SELECT id, status FROM orders WHERE status = ? ORDER BY id";
    private static final String UPDATE_STATUS_SQL = "UPDATE orders SET status = ? WHERE id = ?";

    private final DemoOrderMapper demoOrderMapper;
    private final SqlSentryClientViewStore sqlSentryClientViewStore;

    public DemoOrderService(
            DemoOrderMapper demoOrderMapper,
            SqlSentryClientViewStore sqlSentryClientViewStore) {
        this.demoOrderMapper = demoOrderMapper;
        this.sqlSentryClientViewStore = sqlSentryClientViewStore;
    }

    public DemoOrderQueryResponse queryByStatus(String status) {
        List<DemoOrder> orders = demoOrderMapper.selectByStatus(status);
        return new DemoOrderQueryResponse(orders, resolveDiagnosis(SELECT_BY_STATUS_SQL));
    }

    public DemoOrderUpdateResponse updateStatus(Long id, String status) {
        int affectedRows = demoOrderMapper.updateStatus(id, status);
        return new DemoOrderUpdateResponse(affectedRows, resolveDiagnosis(UPDATE_STATUS_SQL));
    }

    private DemoSqlSentryView resolveDiagnosis(String sql) {
        String fingerprint = SqlFingerprintUtils.fingerprint(sql);
        SqlClientDiagnosis diagnosis = sqlSentryClientViewStore.awaitByFingerprint(fingerprint, 1500L);
        return DemoSqlSentryView.from(diagnosis);
    }
}
