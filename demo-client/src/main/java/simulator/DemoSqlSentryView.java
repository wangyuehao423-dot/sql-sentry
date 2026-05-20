package simulator;

import com.yuehao.sqlsentry.model.SqlClientDiagnosis;

public class DemoSqlSentryView {

    private final String status;
    private final String stage;
    private final String headline;
    private final String summary;
    private final String advice;
    private final String optimizedSqlPreview;

    public DemoSqlSentryView(
            String status,
            String stage,
            String headline,
            String summary,
            String advice,
            String optimizedSqlPreview) {
        this.status = status;
        this.stage = stage;
        this.headline = headline;
        this.summary = summary;
        this.advice = advice;
        this.optimizedSqlPreview = optimizedSqlPreview;
    }

    public static DemoSqlSentryView from(SqlClientDiagnosis diagnosis) {
        if (diagnosis == null) {
            return pending();
        }
        return new DemoSqlSentryView(
                diagnosis.getStatus(),
                diagnosis.getStage(),
                diagnosis.getHeadline(),
                diagnosis.getSummary(),
                diagnosis.getAdvice(),
                diagnosis.getOptimizedSqlPreview());
    }

    public static DemoSqlSentryView pending() {
        return new DemoSqlSentryView(
                "pending",
                "\u8bca\u65ad\u7ed3\u679c\u5c1a\u672a\u8fd4\u56de",
                "\u672c\u6b21\u8bf7\u6c42\u5df2\u89e6\u53d1 SQL \u8bca\u65ad\u3002",
                "\u5f53\u524d\u54cd\u5e94\u5df2\u7ecf\u8fd4\u56de\u4e1a\u52a1\u6570\u636e\uff0cSQL \u8bca\u65ad\u7ed3\u679c\u53ef\u80fd\u8fd8\u5728\u8def\u4e0a\u3002",
                "\u7a0d\u540e\u91cd\u8bd5\u540c\u4e00\u8bf7\u6c42\uff0c\u6216\u76f4\u63a5\u67e5\u770b\u4e0b\u4e00\u6b21\u8fd4\u56de\u4e2d\u7684 sqlSentry \u5b57\u6bb5\u3002",
                null);
    }

    public String getStatus() {
        return status;
    }

    public String getStage() {
        return stage;
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
