package com.yuehao.sqlsentry.mybatis;

import com.yuehao.sqlsentry.annotation.SqlSentry;
import com.yuehao.sqlsentry.client.SqlCapturePrintContext;
import com.yuehao.sqlsentry.client.SqlCaptureReporter;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import com.yuehao.sqlsentry.model.SqlRewriteRule;
import com.yuehao.sqlsentry.rewrite.SqlFingerprintUtils;
import com.yuehao.sqlsentry.rewrite.SqlRewriteLocalCache;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}),
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, org.apache.ibatis.session.ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "batch", args = {Statement.class})
})
public class SqlSentryMyBatisInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SqlSentryMyBatisInterceptor.class);
    private static final String DELEGATE_MAPPED_STATEMENT = "delegate.mappedStatement";
    private static final String MAPPED_STATEMENT = "mappedStatement";
    private static final String ORIGINAL_SQL_CONTEXT_KEY = "__sqlSentryOriginalSql";
    private static final String EXAMPLE_SQL_CONTEXT_KEY = "__sqlSentryExampleSql";
    private static final String AUTO_REPLACED_CONTEXT_KEY = "__sqlSentryAutoReplaced";
    private static final String REWRITE_REASON_CONTEXT_KEY = "__sqlSentryRewriteReason";

    private static volatile Field sqlField;

    private final SqlSentryProperties properties;
    private final SqlRewriteLocalCache localCache;
    private final SqlCaptureReporter sqlCaptureReporter;
    private final Map<String, Boolean> mapperAnnotationCache = new ConcurrentHashMap<>();

    public SqlSentryMyBatisInterceptor(
            SqlSentryProperties properties,
            SqlRewriteLocalCache localCache,
            SqlCaptureReporter sqlCaptureReporter) {
        this.properties = properties;
        this.localCache = localCache;
        this.sqlCaptureReporter = sqlCaptureReporter;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String methodName = invocation.getMethod().getName();
        if ("prepare".equals(methodName)) {
            rewriteSqlIfPresent(invocation.getTarget());
            return invocation.proceed();
        }

        if (!properties.isEnabled() || !properties.isCaptureEnabled() || !isSqlSentryInvocation(invocation.getTarget())) {
            return invocation.proceed();
        }

        long startNanos = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            captureSlowSql(invocation.getTarget(), System.nanoTime() - startNanos);
        }
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof StatementHandler ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {
    }

    private void rewriteSqlIfPresent(Object target) {
        if (!properties.isEnabled() || !properties.isRewriteEnabled() || !isSqlSentryInvocation(target)) {
            return;
        }

        StatementHandler statementHandler = unwrapStatementHandler(target);
        if (statementHandler == null) {
            return;
        }

        BoundSql boundSql = statementHandler.getBoundSql();
        if (boundSql == null || !hasText(boundSql.getSql())) {
            return;
        }

        MappedStatement mappedStatement = resolveMappedStatement(statementHandler);
        String originalSql = SqlFingerprintUtils.normalize(boundSql.getSql());
        storeCaptureContext(boundSql, mappedStatement, originalSql);
        SqlRewriteRule rule = localCache.get(SqlFingerprintUtils.fingerprint(originalSql));
        if (rule == null) {
            return;
        }

        if (!isQueryStatement(mappedStatement, originalSql)) {
            return;
        }

        if (!hasText(rule.getOptimizedSql()) || originalSql.equals(rule.getOptimizedSql())) {
            return;
        }

        try {
            resolveSqlField().set(boundSql, rule.getOptimizedSql());
            boundSql.setAdditionalParameter(AUTO_REPLACED_CONTEXT_KEY, Boolean.TRUE);
            boundSql.setAdditionalParameter(REWRITE_REASON_CONTEXT_KEY, buildRewriteReason(rule));
            printRewriteApplied(rule, originalSql, mappedStatement);
        } catch (IllegalAccessException e) {
            log.warn("Failed to rewrite SQL by reflection: {}", e.toString());
        }
    }

    private void captureSlowSql(Object target, long elapsedNanos) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (elapsedMs < properties.getSlowSqlThresholdMs()) {
            return;
        }

        StatementHandler statementHandler = unwrapStatementHandler(target);
        if (statementHandler == null) {
            return;
        }

        BoundSql boundSql = statementHandler.getBoundSql();
        if (boundSql == null || !hasText(boundSql.getSql())) {
            return;
        }
        MappedStatement mappedStatement = resolveMappedStatement(statementHandler);
        String reportSql = stringContextValue(boundSql, ORIGINAL_SQL_CONTEXT_KEY);
        if (!hasText(reportSql)) {
            reportSql = SqlFingerprintUtils.normalize(boundSql.getSql());
        }

        String exampleSql = stringContextValue(boundSql, EXAMPLE_SQL_CONTEXT_KEY);
        if (!hasText(exampleSql)) {
            exampleSql = renderExecutableSql(boundSql, mappedStatement);
        }

        boolean autoReplaced = booleanContextValue(boundSql, AUTO_REPLACED_CONTEXT_KEY);
        String rewriteReason = stringContextValue(boundSql, REWRITE_REASON_CONTEXT_KEY);
        sqlCaptureReporter.reportSlowSql(
                reportSql,
                elapsedMs,
                new SqlCapturePrintContext(
                        mappedStatement == null ? null : mappedStatement.getId(),
                        exampleSql,
                        autoReplaced,
                        rewriteReason));
    }

    private boolean isSqlSentryInvocation(Object target) {
        StatementHandler statementHandler = unwrapStatementHandler(target);
        if (statementHandler == null) {
            return false;
        }

        MappedStatement mappedStatement = resolveMappedStatement(statementHandler);
        if (mappedStatement == null || !hasText(mappedStatement.getId())) {
            return false;
        }

        return mapperAnnotationCache.computeIfAbsent(mappedStatement.getId(), this::resolveSqlSentryFromMappedStatementId);
    }

    private MappedStatement resolveMappedStatement(StatementHandler statementHandler) {
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        Object value = null;
        if (metaObject.hasGetter(DELEGATE_MAPPED_STATEMENT)) {
            value = metaObject.getValue(DELEGATE_MAPPED_STATEMENT);
        } else if (metaObject.hasGetter(MAPPED_STATEMENT)) {
            value = metaObject.getValue(MAPPED_STATEMENT);
        }
        return value instanceof MappedStatement ? (MappedStatement) value : null;
    }

    private boolean resolveSqlSentryFromMappedStatementId(String mappedStatementId) {
        int lastDot = mappedStatementId.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == mappedStatementId.length() - 1) {
            return false;
        }

        String mapperClassName = mappedStatementId.substring(0, lastDot);
        String mapperMethodName = mappedStatementId.substring(lastDot + 1);

        Class<?> mapperClass = loadMapperClass(mapperClassName);
        if (mapperClass == null) {
            return false;
        }

        if (mapperClass.isAnnotationPresent(SqlSentry.class)) {
            return true;
        }

        for (Method method : mapperClass.getMethods()) {
            if (method.getName().equals(mapperMethodName) && method.isAnnotationPresent(SqlSentry.class)) {
                return true;
            }
        }
        return false;
    }

    private Class<?> loadMapperClass(String mapperClassName) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            if (contextClassLoader != null) {
                return Class.forName(mapperClassName, false, contextClassLoader);
            }
            return Class.forName(mapperClassName);
        } catch (ClassNotFoundException e) {
            log.debug("Unable to load mapper class {}: {}", mapperClassName, e.toString());
            return null;
        }
    }

    private Field resolveSqlField() {
        Field localRef = sqlField;
        if (localRef == null) {
            synchronized (SqlSentryMyBatisInterceptor.class) {
                localRef = sqlField;
                if (localRef == null) {
                    try {
                        localRef = BoundSql.class.getDeclaredField("sql");
                        localRef.setAccessible(true);
                        sqlField = localRef;
                    } catch (NoSuchFieldException e) {
                        throw new IllegalStateException("Cannot find BoundSql.sql field", e);
                    }
                }
            }
        }
        return localRef;
    }

    private StatementHandler unwrapStatementHandler(Object target) {
        Object current = target;
        MetaObject metaObject = SystemMetaObject.forObject(current);
        while (metaObject.hasGetter("h")) {
            Object plugin = metaObject.getValue("h");
            if (plugin == null) {
                break;
            }
            MetaObject pluginMeta = SystemMetaObject.forObject(plugin);
            if (!pluginMeta.hasGetter("target")) {
                break;
            }
            current = pluginMeta.getValue("target");
            metaObject = SystemMetaObject.forObject(current);
        }
        while (metaObject.hasGetter("target")) {
            current = metaObject.getValue("target");
            metaObject = SystemMetaObject.forObject(current);
        }
        return current instanceof StatementHandler ? (StatementHandler) current : null;
    }

    private void printRewriteApplied(SqlRewriteRule rule, String originalSql, MappedStatement mappedStatement) {
        System.out.println("[SQL Sentry] \u7ed3\u8bba: \u6162sql\u5df2\u88ab\u66ff\u6362");
        System.out.println("[SQL Sentry] \u4f4d\u7f6e: " + buildLocation(mappedStatement));
    }

    private String buildLocation(MappedStatement mappedStatement) {
        return mappedStatement == null ? "unknown.mapper.method" : mappedStatement.getId();
    }

    private String buildRewriteReason(SqlRewriteRule rule) {
        String reason = hasText(rule.getSummary()) ? rule.getSummary() : rule.getAdvice();
        return trimTrailingPunctuation(toSingleLine(reason, 48, "\u547d\u4e2d\u6162 SQL \u66ff\u6362\u89c4\u5219"));
    }

    private void storeCaptureContext(BoundSql boundSql, MappedStatement mappedStatement, String originalSql) {
        boundSql.setAdditionalParameter(ORIGINAL_SQL_CONTEXT_KEY, originalSql);
        boundSql.setAdditionalParameter(EXAMPLE_SQL_CONTEXT_KEY, renderExecutableSql(boundSql, mappedStatement));
    }

    private String renderExecutableSql(BoundSql boundSql, MappedStatement mappedStatement) {
        if (boundSql == null || !hasText(boundSql.getSql())) {
            return null;
        }

        String sql = compactSql(boundSql.getSql());
        Object parameterObject = boundSql.getParameterObject();
        if (parameterObject == null) {
            return sql;
        }

        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        if (parameterMappings == null || parameterMappings.isEmpty()) {
            return sql;
        }

        TypeHandlerRegistry typeHandlerRegistry = mappedStatement == null
                ? null
                : mappedStatement.getConfiguration().getTypeHandlerRegistry();
        MetaObject metaObject = mappedStatement == null || parameterObject == null
                ? null
                : mappedStatement.getConfiguration().newMetaObject(parameterObject);

        StringBuilder renderedSql = new StringBuilder(sql.length() + 32);
        int searchFrom = 0;
        for (ParameterMapping parameterMapping : parameterMappings) {
            if (parameterMapping.getMode() == ParameterMode.OUT) {
                continue;
            }

            int placeholderIndex = sql.indexOf('?', searchFrom);
            if (placeholderIndex < 0) {
                break;
            }

            renderedSql.append(sql, searchFrom, placeholderIndex);
            Object value = resolveParameterValue(boundSql, parameterObject, parameterMapping, metaObject, typeHandlerRegistry);
            renderedSql.append(formatSqlLiteral(value));
            searchFrom = placeholderIndex + 1;
        }
        renderedSql.append(sql.substring(searchFrom));
        return renderedSql.toString();
    }

    private Object resolveParameterValue(
            BoundSql boundSql,
            Object parameterObject,
            ParameterMapping parameterMapping,
            MetaObject metaObject,
            TypeHandlerRegistry typeHandlerRegistry) {
        String propertyName = parameterMapping.getProperty();
        if (boundSql.hasAdditionalParameter(propertyName)) {
            return boundSql.getAdditionalParameter(propertyName);
        }
        if (parameterObject == null) {
            return null;
        }
        if (typeHandlerRegistry != null && typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
            return parameterObject;
        }
        if (metaObject == null) {
            return null;
        }
        return metaObject.getValue(propertyName);
    }

    private String formatSqlLiteral(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Date || value instanceof TemporalAccessor) {
            return quoteSqlString(String.valueOf(value));
        }
        if (value instanceof Character || value instanceof CharSequence) {
            return quoteSqlString(String.valueOf(value));
        }
        if (value instanceof byte[]) {
            return "'<binary>'";
        }
        return quoteSqlString(String.valueOf(value));
    }

    private String quoteSqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String compactSql(String sql) {
        if (!hasText(sql)) {
            return null;
        }
        return sql.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
    }

    private String stringContextValue(BoundSql boundSql, String key) {
        if (boundSql == null || !boundSql.hasAdditionalParameter(key)) {
            return null;
        }
        Object value = boundSql.getAdditionalParameter(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanContextValue(BoundSql boundSql, String key) {
        if (boundSql == null || !boundSql.hasAdditionalParameter(key)) {
            return false;
        }
        Object value = boundSql.getAdditionalParameter(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private boolean isQueryStatement(MappedStatement mappedStatement, String normalizedSql) {
        if (mappedStatement != null && mappedStatement.getSqlCommandType() == SqlCommandType.SELECT) {
            return true;
        }
        return normalizedSql.regionMatches(true, 0, "select", 0, "select".length())
                || normalizedSql.regionMatches(true, 0, "with", 0, "with".length());
    }

    private String toSingleLine(String text, int limit, String fallback) {
        if (!hasText(text)) {
            return fallback;
        }
        String compact = text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        if (compact.length() <= limit) {
            return compact;
        }
        return compact.substring(0, limit) + "...";
    }

    private String trimTrailingPunctuation(String text) {
        if (!hasText(text)) {
            return text;
        }

        int end = text.length();
        while (end > 0) {
            char current = text.charAt(end - 1);
            if (current == '.' || current == ',' || current == ';'
                    || current == '\u3002' || current == '\uff0c' || current == '\uff1b'
                    || current == '!' || current == '?' || current == '\uff01' || current == '\uff1f') {
                end--;
                continue;
            }
            break;
        }
        return text.substring(0, end);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
