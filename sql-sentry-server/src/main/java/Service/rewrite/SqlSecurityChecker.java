package Service.rewrite;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class SqlSecurityChecker {

    private static final Pattern DANGEROUS_KEYWORDS = Pattern.compile(
            "(?i)\\b(drop|truncate|delete|alter|create|replace|merge|grant|revoke|call)\\b");
    private static final Pattern MULTI_STATEMENT = Pattern.compile(";\\s*\\S");
    private static final Pattern SAFE_PREFIX = Pattern.compile("(?is)^\\s*(select|with)\\b");
    private static final Pattern SANITIZER_PLACEHOLDER = Pattern.compile(
            "(?i)<(?:str|empty|phone|email|like-pattern|secret|num|hex)>");
    private static final Pattern LIMIT_CLAUSE = Pattern.compile("(?i)\\blimit\\b");

    public boolean isAutoRewriteEligible(String originalSql) {
        String normalized = SqlFingerprintUtils.normalize(originalSql);
        return !normalized.isEmpty() && SAFE_PREFIX.matcher(normalized).find();
    }

    public SecurityDecision checkRewriteCandidate(String originalSql, String optimizedSql) {
        String normalizedCandidate = SqlFingerprintUtils.normalize(optimizedSql);
        if (normalizedCandidate.isEmpty()) {
            return SecurityDecision.reject("empty_sql");
        }
        if (MULTI_STATEMENT.matcher(normalizedCandidate).find()) {
            return SecurityDecision.reject("multi_statement");
        }
        if (DANGEROUS_KEYWORDS.matcher(normalizedCandidate).find()) {
            return SecurityDecision.reject("dangerous_keyword");
        }
        if (!SAFE_PREFIX.matcher(normalizedCandidate).find()) {
            return SecurityDecision.reject("unsafe_statement_type");
        }
        if (SANITIZER_PLACEHOLDER.matcher(normalizedCandidate).find()) {
            return SecurityDecision.reject("sanitized_placeholder");
        }

        String normalizedOriginal = SqlFingerprintUtils.normalize(originalSql);
        if (!LIMIT_CLAUSE.matcher(normalizedOriginal).find()
                && LIMIT_CLAUSE.matcher(normalizedCandidate).find()) {
            return SecurityDecision.reject("added_limit");
        }

        return SecurityDecision.allow(normalizedCandidate);
    }

    public static final class SecurityDecision {
        private final boolean safe;
        private final String reason;
        private final String normalizedSql;

        private SecurityDecision(boolean safe, String reason, String normalizedSql) {
            this.safe = safe;
            this.reason = reason;
            this.normalizedSql = normalizedSql;
        }

        public static SecurityDecision allow(String normalizedSql) {
            return new SecurityDecision(true, "approved", normalizedSql);
        }

        public static SecurityDecision reject(String reason) {
            return new SecurityDecision(false, reason, null);
        }

        public boolean isSafe() {
            return safe;
        }

        public String getReason() {
            return reason;
        }

        public String getNormalizedSql() {
            return normalizedSql;
        }
    }
}
