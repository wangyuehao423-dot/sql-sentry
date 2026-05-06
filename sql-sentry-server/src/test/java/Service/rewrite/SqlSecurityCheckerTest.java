package Service.rewrite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSecurityCheckerTest {

    private final SqlSecurityChecker checker = new SqlSecurityChecker();

    @Test
    void shouldAllowSelectForAutoRewrite() {
        assertTrue(checker.isAutoRewriteEligible(
                "SELECT id FROM orders WHERE status = ? ORDER BY created_at DESC LIMIT ?"));
    }

    @Test
    void shouldRejectNonQueryForAutoRewrite() {
        assertFalse(checker.isAutoRewriteEligible(
                "UPDATE orders SET status = ? WHERE id = ?"));
    }

    @Test
    void shouldRejectDangerousKeyword() {
        SqlSecurityChecker.SecurityDecision decision = checker.checkRewriteCandidate(
                "SELECT id FROM orders",
                "DELETE FROM orders WHERE status = ?");

        assertFalse(decision.isSafe());
        assertEquals("dangerous_keyword", decision.getReason());
    }

    @Test
    void shouldRejectSanitizedPlaceholder() {
        SqlSecurityChecker.SecurityDecision decision = checker.checkRewriteCandidate(
                "SELECT id, status FROM orders WHERE status = 'PAID' ORDER BY id LIMIT 100",
                "SELECT id, status FROM orders WHERE status = '<str>' ORDER BY id LIMIT 100");

        assertFalse(decision.isSafe());
        assertEquals("sanitized_placeholder", decision.getReason());
    }

    @Test
    void shouldRejectAddedLimit() {
        SqlSecurityChecker.SecurityDecision decision = checker.checkRewriteCandidate(
                "SELECT id, status FROM orders WHERE status = 'PAID' ORDER BY id",
                "SELECT id, status FROM orders WHERE status = 'PAID' ORDER BY id LIMIT 1000");

        assertFalse(decision.isSafe());
        assertEquals("added_limit", decision.getReason());
    }
}
