package Service.sanitizer;

import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在不改变 SQL 结构的前提下，将敏感字面量替换为占位符。
 */
@Service
public class SqlSanitizer {

    private static final Pattern SINGLE_QUOTED = Pattern.compile("'((?:''|[^'])*)'");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"((?:\\\\\"|[^\"])*)\"");
    private static final Pattern EMAIL = Pattern.compile("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern HEX_LITERAL = Pattern.compile("0x[0-9a-fA-F]{8,}");
    private static final Pattern LARGE_NUMBER = Pattern.compile("(?<![\\w])\\d{3,}(?:\\.\\d+)?(?![\\w])");

    /**
     * 对单条 SQL 语句执行脱敏。
     *
     * @param sql 原始 SQL
     * @return 脱敏后的 SQL
     */
    public String sanitize(String sql) {
        if (sql == null) {
            return "";
        }

        String sanitized = replaceQuoted(sql, SINGLE_QUOTED, '\'');
        sanitized = replaceQuoted(sanitized, DOUBLE_QUOTED, '"');
        sanitized = EMAIL.matcher(sanitized).replaceAll("<email>");
        sanitized = PHONE.matcher(sanitized).replaceAll("<phone>");
        sanitized = HEX_LITERAL.matcher(sanitized).replaceAll("0x<hex>");
        sanitized = LARGE_NUMBER.matcher(sanitized).replaceAll("<num>");
        return sanitized;
    }

    private String replaceQuoted(String source, Pattern pattern, char quote) {
        Matcher matcher = pattern.matcher(source);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = quote + maskLiteral(matcher.group(1)) + quote;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String maskLiteral(String literal) {
        if (literal == null || literal.isEmpty()) {
            return "<empty>";
        }
        if (PHONE.matcher(literal).find()) {
            return "<phone>";
        }
        if (EMAIL.matcher(literal).find()) {
            return "<email>";
        }
        if (literal.contains("%") || literal.contains("_")) {
            return "<like-pattern>";
        }
        if (literal.length() >= 24) {
            return "<secret>";
        }
        return "<str>";
    }
}
