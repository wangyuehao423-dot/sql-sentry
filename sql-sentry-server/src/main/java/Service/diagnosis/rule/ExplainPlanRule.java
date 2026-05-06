package Service.diagnosis.rule;

import Service.diagnosis.AnalysisContext;
import Service.diagnosis.SqlDiagnosticRule;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在 EXPLAIN 输出中查找高风险信号。
 */
@Component
public class ExplainPlanRule implements SqlDiagnosticRule {

    private static final Pattern ROWS_PATTERN = Pattern.compile("(?im)\\brows\\b\\s*[:=|\\t ]\\s*(\\d+)");

    @Override
    public int getOrder() {
        return 500;
    }

    @Override
    public void apply(AnalysisContext context) {
        if (!context.hasExplainPlan()) {
            context.addRecommendedCheck("请补充 EXPLAIN 输出，并重点关注 type、rows 和 Extra 列。");
            return;
        }

        String upperExplain = context.getUpperExplainPlan();
        boolean hasRiskSignal = false;

        if (upperExplain.contains("USING FILESORT")) {
            hasRiskSignal = true;
            context.addRiskScore(18);
            context.addExplainSignal("EXPLAIN 中包含 Using filesort。");
            context.addFinding(
                    "检测到文件排序",
                    "high",
                    "Extra 列中包含 Using filesort。",
                    "排序无法完全依赖索引顺序完成，可能额外消耗 CPU 和临时空间。",
                    "检查排序字段和联合索引顺序是否合理。");
        }

        if (upperExplain.contains("USING TEMPORARY")) {
            hasRiskSignal = true;
            context.addRiskScore(18);
            context.addExplainSignal("EXPLAIN 中包含 Using temporary。");
            context.addFinding(
                    "检测到临时表",
                    "high",
                    "Extra 列中包含 Using temporary。",
                    "分组或排序过程中使用临时表，可能增加内存或磁盘开销。",
                    "检查 GROUP BY / ORDER BY 设计，以及索引是否能够覆盖该操作。");
        }

        if (upperExplain.contains("USING INDEX CONDITION")) {
            context.addExplainSignal("EXPLAIN 中包含 Using index condition。");
            context.addRecommendedCheck("既然已经启用了 ICP，更应关注扫描行数和回表成本，而不是简单认为问题出在未用索引。");
        }

        if (upperExplain.contains("USING MRR")) {
            context.addExplainSignal("EXPLAIN 中包含 Using MRR。");
            context.addRecommendedCheck("既然已经启用了 MRR，请重点检查扫描行数、过滤选择性和单行宽度。");
        }

        if (upperExplain.contains("USING INDEX")) {
            context.addExplainSignal("EXPLAIN 中包含 Using index。");
        }

        if (upperExplain.contains("USING WHERE")) {
            context.addExplainSignal("EXPLAIN 中包含 Using where。");
        }

        if (upperExplain.contains("TYPE: ALL") || upperExplain.contains("\tALL\t") || upperExplain.matches("(?s).*\\bALL\\b.*")) {
            hasRiskSignal = true;
            context.addRiskScore(25);
            context.addExplainSignal("EXPLAIN 显示可能发生全表扫描。");
            context.addFinding(
                    "全表扫描",
                    "high",
                    "在 EXPLAIN 中检测到 type=ALL 或等价信号。",
                    "优化器大概率没有使用高选择性索引，扫描成本会随着表规模增长而上升。",
                    "检查过滤条件、索引设计和表统计信息。");
        }

        long maxRows = extractMaxRows(context.getNormalizedExplainPlan());
        if (maxRows >= 10000L) {
            hasRiskSignal = true;
            context.addRiskScore(maxRows >= 100000L ? 18 : 10);
            context.addExplainSignal("EXPLAIN 估算扫描行数为 " + maxRows + "。");
            context.addFinding(
                    "扫描行数过大",
                    maxRows >= 100000L ? "high" : "medium",
                    "EXPLAIN 中 rows 达到 " + maxRows + "。",
                    "当前过滤条件选择性可能不足，扫描范围可能过大。",
                    "缩小扫描范围，并确认目标联合索引是否真的命中。");
        }

        if (hasRiskSignal) {
            context.addRecommendedCheck("把 rows、type 和 Extra 作为改写前后对比的基线。");
        } else {
            context.addExplainSignal("已提供 EXPLAIN，但未命中高风险关键词。");
        }
    }

    private long extractMaxRows(String explainPlan) {
        Matcher matcher = ROWS_PATTERN.matcher(explainPlan);
        long max = 0L;
        while (matcher.find()) {
            max = Math.max(max, parseLong(matcher.group(1)));
        }
        return max;
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
