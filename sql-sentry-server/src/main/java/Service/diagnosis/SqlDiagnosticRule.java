package Service.diagnosis;

/**
 * 每条本地 SQL 诊断规则都需要实现的约定接口。
 */
public interface SqlDiagnosticRule {

    /**
     * 返回执行顺序，值越小越先执行。
     *
     * @return 稳定的排序值
     */
    int getOrder();

    /**
     * 将当前规则应用到共享分析上下文中。
     *
     * @param context 当前 SQL 分析对应的可变上下文
     */
    void apply(AnalysisContext context);
}
