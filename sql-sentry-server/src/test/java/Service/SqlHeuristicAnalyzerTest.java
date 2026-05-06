package Service;

import Service.diagnosis.rule.ExplainPlanRule;
import Service.diagnosis.rule.JoinAndSubqueryRule;
import Service.diagnosis.rule.MutationSafetyRule;
import Service.diagnosis.rule.PaginationAndOrderingRule;
import Service.diagnosis.rule.ProjectionAndPredicateRule;
import Service.sanitizer.SqlSanitizer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlHeuristicAnalyzer} 的单元测试。
 */
class SqlHeuristicAnalyzerTest {

    // 直接构建分析器，避免测试依赖 Spring 上下文。
    private final SqlHeuristicAnalyzer analyzer = new SqlHeuristicAnalyzer(
            Arrays.asList(
                    new ProjectionAndPredicateRule(),
                    new PaginationAndOrderingRule(),
                    new JoinAndSubqueryRule(),
                    new MutationSafetyRule(),
                    new ExplainPlanRule()
            ),
            new SqlSanitizer()
    );

    /**
     * 验证前导通配搜索和排序压力的识别能力。
     */
    @Test
    void shouldDetectLeadingWildcardAndOrderingPressure() {
        SqlHeuristicAnalyzer.AnalysisSnapshot snapshot = analyzer.analyze(
                "SELECT * FROM t_user WHERE nickname LIKE '%test%' ORDER BY created_at DESC LIMIT 50",
                null
        );

        assertEquals("high", snapshot.getRiskLevel());
        assertTrue(snapshot.getFindings().stream().anyMatch(item -> "LIKE 前导通配".equals(item.getTitle())));
        assertTrue(snapshot.getFindings().stream().anyMatch(item -> "排序压力".equals(item.getTitle())));
    }

    /**
     * 验证嵌套子查询和深分页的识别能力。
     */
    @Test
    void shouldDetectSubqueryAndDeepPagination() {
        SqlHeuristicAnalyzer.AnalysisSnapshot snapshot = analyzer.analyze(
                "SELECT id FROM orders WHERE user_id IN (SELECT user_id FROM vip_user) ORDER BY created_at DESC LIMIT 5000, 20",
                null
        );

        assertTrue(snapshot.getFindings().stream().anyMatch(item -> "存在子查询路径".equals(item.getTitle())));
        assertTrue(snapshot.getFindings().stream().anyMatch(item -> "深分页".equals(item.getTitle())));
        assertTrue(snapshot.getRecommendedChecks().stream().anyMatch(item -> item.contains("EXPLAIN")));
    }

    /**
     * 验证缺少 WHERE 的 UPDATE 会被标记为 critical。
     */
    @Test
    void shouldMarkUnsafeMutationAsCritical() {
        SqlHeuristicAnalyzer.AnalysisSnapshot snapshot = analyzer.analyze(
                "UPDATE t_user SET status = 0",
                null
        );

        assertEquals("critical", snapshot.getRiskLevel());
        assertTrue(snapshot.getFindings().stream().anyMatch(item -> "变更语句缺少 WHERE".equals(item.getTitle())));
    }

    /**
     * 验证 EXPLAIN 附加信号的解析能力。
     */
    @Test
    void shouldParseExplainExtraSignals() {
        SqlHeuristicAnalyzer.AnalysisSnapshot snapshot = analyzer.analyze(
                "SELECT id FROM orders WHERE status = 1 ORDER BY created_at DESC LIMIT 20",
                "type: range\nrows: 154320\nExtra: Using index condition; Using MRR; Using filesort"
        );

        assertTrue(snapshot.getExplainSignals().stream().anyMatch(item -> item.contains("Using index condition")));
        assertTrue(snapshot.getExplainSignals().stream().anyMatch(item -> item.contains("Using MRR")));
        assertTrue(snapshot.getFindings().stream().anyMatch(item -> "检测到文件排序".equals(item.getTitle())));
    }

    /**
     * 验证在 AI 提示前会先对敏感字面量做脱敏处理。
     */
    @Test
    void shouldSanitizeSensitiveLiteralsForAiPrompt() {
        SqlHeuristicAnalyzer.AnalysisSnapshot snapshot = analyzer.analyze(
                "SELECT * FROM t_user WHERE phone = '13800138000' AND email = 'user@example.com' AND trace_id = 'abcdefabcdefabcdefabcdef' AND amount = 998877",
                null
        );

        assertTrue(snapshot.getSanitizedSql().contains("<phone>"));
        assertTrue(snapshot.getSanitizedSql().contains("<email>"));
        assertTrue(snapshot.getSanitizedSql().contains("<secret>"));
        assertTrue(snapshot.getSanitizedSql().contains("<num>"));
    }
}
