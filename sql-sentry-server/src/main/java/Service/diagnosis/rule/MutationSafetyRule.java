package Service.diagnosis.rule;

import Service.diagnosis.AnalysisContext;
import Service.diagnosis.SqlDiagnosticRule;
import org.springframework.stereotype.Component;

/**
 * 标记缺少行过滤条件的高破坏性变更语句。
 */
@Component
public class MutationSafetyRule implements SqlDiagnosticRule {

    @Override
    public int getOrder() {
        return 400;
    }

    @Override
    public void apply(AnalysisContext context) {
        String upperSql = context.getUpperSql();
        if ((upperSql.startsWith("UPDATE") || upperSql.startsWith("DELETE")) && !upperSql.contains(" WHERE ")) {
            context.ensureRiskFloor(95);
            context.addFinding(
                    "变更语句缺少 WHERE",
                    "critical",
                    "检测到缺少 WHERE 的 UPDATE/DELETE 语句。",
                    "这可能直接修改或删除整张表，因此被视为严重风险。",
                    "先补充精确的 WHERE 条件，并在安全环境中确认影响行数。");
        }
    }
}
