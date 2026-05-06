package Service.rewrite;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 服务端 SQL 指纹辅助工具。
 * 它必须与 starter 侧实现保持逐字节兼容。
 */
public final class SqlFingerprintUtils {

    private SqlFingerprintUtils() {
    }

    /**
     * 为一条 SQL 语句生成指纹。
     *
     * @param sql 原始 SQL
     * @return 归一化后 SQL 的 MD5 哈希值
     */
    public static String fingerprint(String sql) {
        String normalized = normalize(sql);
        return DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 在哈希前先归一化 SQL 中的空白字符。
     *
     * @param sql 原始 SQL
     * @return 压缩空白字符后的 SQL
     */
    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.trim().replaceAll("\\s+", " ");
    }
}
