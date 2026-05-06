package Service.diagnosis.rule;

import Service.diagnosis.AnalysisContext;
import Service.diagnosis.SqlDiagnosticRule;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 标记常见会破坏优良执行计划的投影与谓词模式。
 */
@Component
public class ProjectionAndPredicateRule implements SqlDiagnosticRule {

    private static final Pattern SELECT_ALL_PATTERN = Pattern.compile("(?is)^\\s*select\\s+\\*\\s+from\\b");
    private static final Pattern LEADING_WILDCARD_PATTERN = Pattern.compile("(?is)like\\s+['\"][%_].*?['\"]");
    private static final Pattern OR_CONDITION_PATTERN = Pattern.compile("(?is)\\bwhere\\b.*\\bor\\b");
    private static final Pattern FUNCTION_ON_COLUMN_PATTERN = Pattern.compile("(?is)\\bwhere\\b.*\\b(date|substr|substring|upper|lower|ifnull|coalesce|cast)\\s*\\(");

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void apply(AnalysisContext context) {
        String sql = context.getNormalizedSql();

        if (SELECT_ALL_PATTERN.matcher(sql).find()) {
            context.addRiskScore(10);
            context.addFinding(
                    "查询了全部列",
                    "medium",
                    "检测到 SELECT *。",
                    "读取不必要的列会增加 IO、网络传输和潜在回表成本。",
                    "只查询业务路径真正需要的列。");
        }

        if (LEADING_WILDCARD_PATTERN.matcher(sql).find()) {
            context.addRiskScore(35);
            context.addFinding(
                    "LIKE 前导通配",
                    "high",
                    "检测到 LIKE 前导通配，例如 '%xxx' 或 '%xxx%'。",
                    "B-Tree 索引通常无法被高效利用，容易退化为全表扫描。",
                    "考虑全文检索、专用搜索服务，或改写为避免前导通配的查询方式。");
        }

        if (OR_CONDITION_PATTERN.matcher(sql).find()) {
            context.addRiskScore(10);
            context.addFinding(
                    "OR 条件谓词",
                    "medium",
                    "检测到 WHERE ... OR ... 模式。",
                    "复杂的 OR 条件常会降低索引选择性，并增加优化器决策难度。",
                    "考虑拆分为 UNION ALL，或确保每个分支都能利用索引。");
        }

        if (FUNCTION_ON_COLUMN_PATTERN.matcher(sql).find()) {
            context.addRiskScore(12);
            context.addFinding(
                    "索引列上使用函数",
                    "medium",
                    "检测到在 WHERE 中直接对列使用函数。",
                    "列上套函数通常会阻断索引使用，并增加扫描成本。",
                    "尽量把计算移到常量侧，或在合适时增加派生字段/物化字段。");
        }
    }
}
