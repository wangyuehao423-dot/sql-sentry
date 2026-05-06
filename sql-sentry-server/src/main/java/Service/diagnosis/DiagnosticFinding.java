package Service.diagnosis;

/**
 * 单条命中 SQL 风险结论的结构化表示。
 */
public class DiagnosticFinding {

    // 结论的简短标题。
    private final String title;
    // 风险级别：low、medium、high 或 critical。
    private final String severity;
    // 触发该结论的具体证据。
    private final String evidence;
    // 该问题可能带来的运行影响。
    private final String impact;
    // 建议的修复方向。
    private final String suggestion;

    public DiagnosticFinding(String title, String severity, String evidence, String impact, String suggestion) {
        this.title = title;
        this.severity = severity;
        this.evidence = evidence;
        this.impact = impact;
        this.suggestion = suggestion;
    }

    public String getTitle() {
        return title;
    }

    public String getSeverity() {
        return severity;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getImpact() {
        return impact;
    }

    public String getSuggestion() {
        return suggestion;
    }
}
