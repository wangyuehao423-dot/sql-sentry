package com.yuehao.sqlsentry.mybatis;

import com.yuehao.sqlsentry.annotation.SqlSentry;
import com.yuehao.sqlsentry.client.SqlCaptureReporter;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import com.yuehao.sqlsentry.model.SqlRewriteRule;
import com.yuehao.sqlsentry.rewrite.SqlFingerprintUtils;
import com.yuehao.sqlsentry.rewrite.SqlRewriteLocalCache;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
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
        SqlRewriteRule rule = localCache.get(SqlFingerprintUtils.fingerprint(originalSql));
        if (rule == null) {
            return;
        }

        if (!isQueryStatement(mappedStatement, originalSql)) {
            if (isMutationStatement(mappedStatement, originalSql)) {
                printAdviceOnly(rule, originalSql);
            }
            return;
        }

        if (!hasText(rule.getOptimizedSql()) || originalSql.equals(rule.getOptimizedSql())) {
            return;
        }

        try {
            resolveSqlField().set(boundSql, rule.getOptimizedSql());
            printRewriteApplied(rule, originalSql);
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
        sqlCaptureReporter.reportSlowSql(boundSql.getSql(), elapsedMs);
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

    private void printAdviceOnly(SqlRewriteRule rule, String originalSql) {
        String advice = hasText(rule.getAdvice()) ? rule.getAdvice() : rule.getSummary();
        String briefAdvice = toSingleLine(advice, 96, "This SQL has optimization opportunities. Check EXPLAIN first.");
        String exampleSql = hasText(rule.getExampleSql()) ? rule.getExampleSql() : originalSql;

        System.out.println("[SQL Sentry] Advice: " + briefAdvice);
        System.out.println("[SQL Sentry] SQL: " + toSingleLine(exampleSql, 120, exampleSql));
    }

    private void printRewriteApplied(SqlRewriteRule rule, String originalSql) {
        String summary = toSingleLine(rule.getSummary(), 48, "Applied SQL rewrite rule");
        String advice = toSingleLine(rule.getAdvice(), 96, "Verify that the result set stays equivalent to the original SQL.");

        System.out.println("[SQL Sentry] Rewrite applied: " + summary);
        System.out.println("[SQL Sentry] Advice: " + advice);
        System.out.println("[SQL Sentry] Original SQL: " + toSingleLine(originalSql, 120, originalSql));
    }

    private boolean isQueryStatement(MappedStatement mappedStatement, String normalizedSql) {
        if (mappedStatement != null && mappedStatement.getSqlCommandType() == SqlCommandType.SELECT) {
            return true;
        }
        return normalizedSql.regionMatches(true, 0, "select", 0, "select".length())
                || normalizedSql.regionMatches(true, 0, "with", 0, "with".length());
    }

    private boolean isMutationStatement(MappedStatement mappedStatement, String normalizedSql) {
        if (mappedStatement != null) {
            SqlCommandType commandType = mappedStatement.getSqlCommandType();
            if (commandType == SqlCommandType.INSERT
                    || commandType == SqlCommandType.UPDATE
                    || commandType == SqlCommandType.DELETE) {
                return true;
            }
        }

        return normalizedSql.regionMatches(true, 0, "insert", 0, "insert".length())
                || normalizedSql.regionMatches(true, 0, "update", 0, "update".length())
                || normalizedSql.regionMatches(true, 0, "delete", 0, "delete".length())
                || normalizedSql.regionMatches(true, 0, "replace", 0, "replace".length())
                || normalizedSql.regionMatches(true, 0, "merge", 0, "merge".length())
                || normalizedSql.regionMatches(true, 0, "truncate", 0, "truncate".length())
                || normalizedSql.regionMatches(true, 0, "alter", 0, "alter".length())
                || normalizedSql.regionMatches(true, 0, "drop", 0, "drop".length())
                || normalizedSql.regionMatches(true, 0, "create", 0, "create".length());
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
