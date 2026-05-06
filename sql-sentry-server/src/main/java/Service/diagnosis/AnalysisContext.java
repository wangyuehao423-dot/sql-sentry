package Service.diagnosis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一条 SQL 在所有诊断规则之间共享的可变分析上下文。
 */
public class AnalysisContext {

    // 归一化后的原始 SQL 文本。
    private final String normalizedSql;
    // 面向模型提示词使用的脱敏 SQL 文本。
    private final String sanitizedSql;
    // 归一化后的 EXPLAIN 输出；如果存在。
    private final String normalizedExplainPlan;
    // 转为大写的 SQL，用于大小写不敏感的检查。
    private final String upperSql;
    // 转为大写的 EXPLAIN，用于大小写不敏感的检查。
    private final String upperExplainPlan;
    // 按发现顺序记录的命中结论。
    private final List<DiagnosticFinding> findings = new ArrayList<>();
    // 去重后且保留插入顺序的 EXPLAIN 信号集合。
    private final Set<String> explainSignals = new LinkedHashSet<>();
    // 去重后且保留插入顺序的后续检查建议。
    private final Set<String> recommendedChecks = new LinkedHashSet<>();
    // 从一个较小的非零基线开始，再由规则逐步累加风险。
    private int riskScore = 8;

    /**
     * 创建新的分析上下文。
     *
     * @param normalizedSql 归一化后的 SQL 文本
     * @param sanitizedSql 脱敏后的 SQL 文本
     * @param normalizedExplainPlan 归一化后的 EXPLAIN 文本
     */
    public AnalysisContext(String normalizedSql, String sanitizedSql, String normalizedExplainPlan) {
        this.normalizedSql = normalizedSql;
        this.sanitizedSql = sanitizedSql;
        this.normalizedExplainPlan = normalizedExplainPlan;
        this.upperSql = normalizedSql.toUpperCase(Locale.ROOT);
        this.upperExplainPlan = normalizedExplainPlan == null ? "" : normalizedExplainPlan.toUpperCase(Locale.ROOT);
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

    public String getUpperSql() {
        return upperSql;
    }

    public String getUpperExplainPlan() {
        return upperExplainPlan;
    }

    public List<DiagnosticFinding> getFindings() {
        return findings;
    }

    public Set<String> getExplainSignals() {
        return explainSignals;
    }

    public Set<String> getRecommendedChecks() {
        return recommendedChecks;
    }

    public int getRiskScore() {
        return riskScore;
    }

    /**
     * 为当前风险分数累加增量。
     *
     * @param delta 单条规则贡献的分数增量
     */
    public void addRiskScore(int delta) {
        riskScore += delta;
    }

    /**
     * 确保分数至少达到指定下限。
     * 这用于 UPDATE 或 DELETE 缺少 WHERE 等必须拦截的高风险场景。
     *
     * @param value 最低风险分数
     */
    public void ensureRiskFloor(int value) {
        riskScore = Math.max(riskScore, value);
    }

    /**
     * 返回是否存在 EXPLAIN 输出。
     *
     * @return 当 EXPLAIN 文本存在时返回 true
     */
    public boolean hasExplainPlan() {
        return normalizedExplainPlan != null && !normalizedExplainPlan.isEmpty();
    }

    /**
     * 判断 EXPLAIN 是否包含指定标记。
     *
     * @param token 要检查的标记
     * @return 当标记存在时返回 true
     */
    public boolean explainContains(String token) {
        return upperExplainPlan.contains(token.toUpperCase(Locale.ROOT));
    }

    /**
     * 判断 SQL 是否包含指定标记。
     *
     * @param token 要检查的标记
     * @return 当标记存在时返回 true
     */
    public boolean sqlContains(String token) {
        return upperSql.contains(token.toUpperCase(Locale.ROOT));
    }

    /**
     * 追加一条结构化结论。
     *
     * @param title 结论标题
     * @param severity low、medium、high 或 critical
     * @param evidence 规则命中的原因
     * @param impact 预期的性能或安全影响
     * @param suggestion 建议的修复方向
     */
    public void addFinding(String title, String severity, String evidence, String impact, String suggestion) {
        findings.add(new DiagnosticFinding(title, severity, evidence, impact, suggestion));
    }

    /**
     * 记录一条 EXPLAIN 信号。
     *
     * @param signal 信号文本
     */
    public void addExplainSignal(String signal) {
        if (signal != null && !signal.trim().isEmpty()) {
            explainSignals.add(signal.trim());
        }
    }

    /**
     * 记录一条建议执行的后续检查。
     *
     * @param check 建议文本
     */
    public void addRecommendedCheck(String check) {
        if (check != null && !check.trim().isEmpty()) {
            recommendedChecks.add(check.trim());
        }
    }
}
