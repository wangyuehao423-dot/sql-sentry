package Service;

import Service.diagnosis.AnalysisContext;
import Service.diagnosis.DiagnosticFinding;
import Service.diagnosis.SqlDiagnosticRule;
import Service.sanitizer.SqlSanitizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 本地规则分析器，在进入 LLM 阶段前先为 SQL 打分。
 */
@Service
public class SqlHeuristicAnalyzer {

    // 已注册的诊断规则，仅排序一次以保证执行顺序稳定。
    private final List<SqlDiagnosticRule> rules;
    // SQL 发送给模型前，先对字面量做脱敏处理。
    private final SqlSanitizer sqlSanitizer;

    /**
     * 创建分析器并固定规则顺序。
     *
     * @param rules Spring 发现的诊断规则
     * @param sqlSanitizer 面向模型的 SQL 脱敏器
     */
    public SqlHeuristicAnalyzer(List<SqlDiagnosticRule> rules, SqlSanitizer sqlSanitizer) {
        List<SqlDiagnosticRule> orderedRules = new ArrayList<>(rules);
        orderedRules.sort(Comparator.comparingInt(SqlDiagnosticRule::getOrder));
        this.rules = Collections.unmodifiableList(orderedRules);
        this.sqlSanitizer = sqlSanitizer;
    }

    /**
     * 为一条 SQL 生成完整的启发式分析结果。
     *
     * @param sql 原始 SQL 文本
     * @param explainPlan 可选的 EXPLAIN 输出
     * @return 不可变的分析快照
     */
    public AnalysisSnapshot analyze(String sql, String explainPlan) {
        // 先统一空白字符，确保每条规则看到的 SQL 形态一致。
        String normalizedSql = normalizeWhitespace(sql);
        // SQL 被复用到 AI 提示词前，先对字面量脱敏。
        String sanitizedSql = normalizeWhitespace(sqlSanitizer.sanitize(normalizedSql));
        // 对 EXPLAIN 做保守归一化，避免丢失有价值的线索。
        String normalizedExplain = normalizeExplain(explainPlan);

        AnalysisContext context = new AnalysisContext(normalizedSql, sanitizedSql, normalizedExplain);

        // 依次应用规则，累积结论、分数和后续检查建议。
        for (SqlDiagnosticRule rule : rules) {
            rule.apply(context);
        }

        // 始终提醒调用方对比改写前后的执行计划。
        context.addRecommendedCheck("对比改写前后的 EXPLAIN 输出，重点关注 rows、type 和 Extra。");

        // 不返回空结果，而是补充一个低风险兜底结论。
        if (context.getFindings().isEmpty()) {
            context.addFinding(
                    "未命中明显高风险规则",
                    "low",
                    "这条 SQL 没有触发已配置的高风险启发式规则。",
                    "这并不代表 SQL 一定很快，真实表现仍取决于数据量、索引和执行计划。",
                    "在决定是否需要改写前，先检查索引、数据分布和 EXPLAIN 输出。");
        }

        int riskScore = Math.min(100, Math.max(0, context.getRiskScore()));
        String riskLevel = mapRiskLevel(riskScore);
        String summary = buildSummary(context.getFindings(), context.getExplainSignals().size(), riskLevel, riskScore);

        return new AnalysisSnapshot(
                normalizedSql,
                sanitizedSql,
                normalizedExplain,
                riskScore,
                riskLevel,
                summary,
                context.getFindings(),
                new ArrayList<>(context.getExplainSignals()),
                new ArrayList<>(context.getRecommendedChecks())
        );
    }

    /**
     * 将连续空白字符压缩为单个空格。
     *
     * @param text 输入文本
     * @return 归一化后的文本；如果输入为 null，则返回空字符串
     */
    private String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * 在保留原始结构的前提下归一化 EXPLAIN 文本。
     *
     * @param explainPlan 原始 EXPLAIN 输出
     * @return 去除首尾空白后的 EXPLAIN 文本；若未提供则返回 null
     */
    private String normalizeExplain(String explainPlan) {
        if (explainPlan == null || explainPlan.trim().isEmpty()) {
            return null;
        }
        return explainPlan.trim();
    }

    /**
     * 将数值得分映射为稳定的风险标签。
     *
     * @param riskScore 0 到 100 范围内的分数
     * @return low、medium、high 或 critical
     */
    private String mapRiskLevel(int riskScore) {
        if (riskScore >= 85) {
            return "critical";
        }
        if (riskScore >= 65) {
            return "high";
        }
        if (riskScore >= 35) {
            return "medium";
        }
        return "low";
    }

    /**
     * 生成简洁、便于阅读的分析摘要。
     *
     * @param findings 命中的结论列表
     * @param explainSignalCount 收集到的 EXPLAIN 信号数量
     * @param riskLevel 最终风险级别
     * @param riskScore 最终风险分数
     * @return 摘要文本
     */
    private String buildSummary(List<DiagnosticFinding> findings, int explainSignalCount, String riskLevel, int riskScore) {
        int highRiskCount = 0;
        for (DiagnosticFinding finding : findings) {
            if ("high".equalsIgnoreCase(finding.getSeverity()) || "critical".equalsIgnoreCase(finding.getSeverity())) {
                highRiskCount++;
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append("共识别到 ")
                .append(findings.size())
                .append(" 个风险点，其中 ")
                .append(highRiskCount)
                .append(" 个为高风险或严重风险。整体风险级别为 ")
                .append(riskLevel.toUpperCase(Locale.ROOT))
                .append("，风险分数 ")
                .append(riskScore)
                .append("/100。");

        if (explainSignalCount > 0) {
            builder.append(" 同时包含 ").append(explainSignalCount).append(" 条 EXPLAIN 信号。");
        }
        return builder.toString();
    }

    /**
     * 单次启发式分析对应的不可变结果对象。
     */
    public static final class AnalysisSnapshot {
        // 归一化后的 SQL，用于指纹计算和持久化。
        private final String normalizedSql;
        // 脱敏后的 SQL，可安全发送给模型。
        private final String sanitizedSql;
        // 去除首尾空白后的 EXPLAIN 输出；如果存在。
        private final String normalizedExplainPlan;
        // 已限制在 0 到 100 区间内的风险分数。
        private final int riskScore;
        // 根据分数映射得到的文本风险级别。
        private final String riskLevel;
        // 供日志和 UI 载荷使用的可读摘要。
        private final String summary;
        // 规则产出的结构化结论。
        private final List<DiagnosticFinding> findings;
        // 分析过程中提取出的 EXPLAIN 级别信号。
        private final List<String> explainSignals;
        // 推荐给调用方的后续检查项。
        private final List<String> recommendedChecks;

        private AnalysisSnapshot(
                String normalizedSql,
                String sanitizedSql,
                String normalizedExplainPlan,
                int riskScore,
                String riskLevel,
                String summary,
                List<DiagnosticFinding> findings,
                List<String> explainSignals,
                List<String> recommendedChecks) {
            this.normalizedSql = normalizedSql;
            this.sanitizedSql = sanitizedSql;
            this.normalizedExplainPlan = normalizedExplainPlan;
            this.riskScore = riskScore;
            this.riskLevel = riskLevel;
            this.summary = summary;
            this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
            this.explainSignals = Collections.unmodifiableList(new ArrayList<>(explainSignals));
            this.recommendedChecks = Collections.unmodifiableList(new ArrayList<>(recommendedChecks));
        }

        public String getNormalizedSql() {
            return normalizedSql;
        }

        public String getSanitizedSql() {
            return sanitizedSql;
        }

        public String getNormalizedExplainPlan() {
            return normalizedExplainPlan;
        }

        public int getRiskScore() {
            return riskScore;
        }

        public String getRiskLevel() {
            return riskLevel;
        }

        public String getSummary() {
            return summary;
        }

        public List<DiagnosticFinding> getFindings() {
            return findings;
        }

        public List<String> getExplainSignals() {
            return explainSignals;
        }

        public List<String> getRecommendedChecks() {
            return recommendedChecks;
        }

        /**
         * 返回本次分析是否附带了 EXPLAIN 输出。
         *
         * @return 当 EXPLAIN 文本存在时返回 true
         */
        public boolean isExplainProvided() {
            return normalizedExplainPlan != null && !normalizedExplainPlan.isEmpty();
        }
    }
}
