package Service.diagnosis.rule;

import Service.diagnosis.AnalysisContext;
import Service.diagnosis.SqlDiagnosticRule;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 标记嵌套子查询和过宽的 JOIN 执行计划。
 */
@Component
public class JoinAndSubqueryRule implements SqlDiagnosticRule {

    private static final Pattern SUBQUERY_PATTERN = Pattern.compile("(?is)\\b(in|exists)\\s*\\(\\s*select\\b");

    @Override
    public int getOrder() {
        return 300;
    }

    @Override
    public void apply(AnalysisContext context) {
        String sql = context.getNormalizedSql();
        String upperSql = context.getUpperSql();

        if (SUBQUERY_PATTERN.matcher(sql).find()) {
            context.addRiskScore(12);
            context.addFinding(
                    "存在子查询路径",
                    "medium",
                    "检测到 IN (SELECT ...) 或 EXISTS (SELECT ...) 模式。",
                    "嵌套子查询可能触发重复扫描，或让执行策略变得低效。",
                    "评估是否改写为 JOIN 或半连接形式会更稳定。");
        }

        int joinCount = countOccurrences(upperSql, " JOIN ");
        if (joinCount >= 3) {
            context.addRiskScore(10);
            context.addFinding(
                    "多表关联过多",
                    "medium",
                    "检测到 " + joinCount + " 个 JOIN 子句。",
                    "过多 JOIN 会增加优化器搜索空间和中间结果处理成本。",
                    "检查 JOIN 顺序、关联索引以及谓词下推空间。");
        }
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
