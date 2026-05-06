package Service.diagnosis.rule;

import Service.diagnosis.AnalysisContext;
import Service.diagnosis.SqlDiagnosticRule;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标记排序、分组和分页中常见的扩展性较差模式。
 */
@Component
public class PaginationAndOrderingRule implements SqlDiagnosticRule {

    private static final Pattern LARGE_OFFSET_PATTERN = Pattern.compile("(?is)limit\\s+(\\d+)\\s*,\\s*(\\d+)|limit\\s+(\\d+)\\s+offset\\s+(\\d+)");

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public void apply(AnalysisContext context) {
        String upperSql = context.getUpperSql();
        if (upperSql.contains("ORDER BY")) {
            boolean hasLimit = upperSql.contains("LIMIT");
            context.addRiskScore(hasLimit ? 12 : 18);
            context.addFinding(
                    "排序压力",
                    hasLimit ? "medium" : "high",
                    hasLimit ? "检测到带 LIMIT 的 ORDER BY。" : "检测到未带 LIMIT 的 ORDER BY。",
                    "如果排序字段没有被合适的索引覆盖，MySQL 可能执行 filesort，或对大结果集排序。",
                    "检查 ORDER BY 字段是否与可用的联合索引顺序一致。");
            context.addRecommendedCheck("确认 ORDER BY 字段是否能由同一个联合索引满足。");
        }

        Matcher offsetMatcher = LARGE_OFFSET_PATTERN.matcher(context.getNormalizedSql());
        if (offsetMatcher.find()) {
            long offset = extractOffset(offsetMatcher);
            if (offset >= 1000L) {
                context.addRiskScore(14);
                context.addFinding(
                        "深分页",
                        "high",
                        "检测到大偏移量分页模式，offset=" + offset + "。",
                        "数据库在返回当前页前必须跳过大量记录，成本会随分页深度持续上升。",
                        "优先考虑 Keyset 分页或基于主键范围的分页策略。");
            }
        }

        if (upperSql.contains("GROUP BY")) {
            context.addRecommendedCheck("检查 GROUP BY 字段是否与过滤和排序共享索引前缀。");
        }
    }

    private long extractOffset(Matcher matcher) {
        if (matcher.group(1) != null) {
            return parseLong(matcher.group(1));
        }
        if (matcher.group(4) != null) {
            return parseLong(matcher.group(4));
        }
        return 0L;
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
