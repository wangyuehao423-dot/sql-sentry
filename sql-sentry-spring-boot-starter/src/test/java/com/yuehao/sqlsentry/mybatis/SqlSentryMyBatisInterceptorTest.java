package com.yuehao.sqlsentry.mybatis;

import com.yuehao.sqlsentry.annotation.SqlSentry;
import com.yuehao.sqlsentry.client.SqlCaptureReporter;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import com.yuehao.sqlsentry.model.SqlRewriteRule;
import com.yuehao.sqlsentry.rewrite.SqlFingerprintUtils;
import com.yuehao.sqlsentry.rewrite.SqlRewriteLocalCache;
import okhttp3.OkHttpClient;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSentryMyBatisInterceptorTest {

    @Test
    void shouldRewriteBoundSqlBeforePrepare() throws Throwable {
        SqlSentryProperties properties = new SqlSentryProperties();
        properties.setEnabled(true);
        SqlRewriteLocalCache localCache = new SqlRewriteLocalCache(properties);
        localCache.put(new SqlRewriteRule(
                SqlFingerprintUtils.fingerprint("SELECT * FROM orders WHERE status = ?"),
                "SELECT id FROM orders WHERE status = ?",
                null,
                "reduce unnecessary selected columns",
                null,
                "approved",
                "SELECT id FROM orders WHERE status = ?"));

        SqlCaptureReporter reporter = new SqlCaptureReporter(properties, new OkHttpClient());
        SqlSentryMyBatisInterceptor interceptor = new SqlSentryMyBatisInterceptor(properties, localCache, reporter);

        BoundSql boundSql = new BoundSql(
                new Configuration(),
                "SELECT * FROM orders WHERE status = ?",
                Collections.<ParameterMapping>emptyList(),
                null);
        MappedStatement mappedStatement = new MappedStatement.Builder(
                new Configuration(),
                AnnotatedMapper.class.getName() + ".findByStatus",
                new StaticSqlSource(new Configuration(), "SELECT * FROM orders WHERE status = ?"),
                SqlCommandType.SELECT
        ).build();
        TestStatementHandler statementHandler = new TestStatementHandler(boundSql, mappedStatement);
        Method prepareMethod = StatementHandler.class.getMethod("prepare", Connection.class, Integer.class);
        Invocation invocation = new Invocation(statementHandler, prepareMethod, new Object[]{null, Integer.valueOf(30)});

        interceptor.intercept(invocation);

        assertEquals("SELECT id FROM orders WHERE status = ?", boundSql.getSql());
    }

    @Test
    void shouldPrintAdviceForMutationSqlWithoutRewriting() throws Throwable {
        SqlSentryProperties properties = new SqlSentryProperties();
        properties.setEnabled(true);
        SqlRewriteLocalCache localCache = new SqlRewriteLocalCache(properties);
        localCache.put(new SqlRewriteRule(
                SqlFingerprintUtils.fingerprint("UPDATE orders SET status = ? WHERE id = ?"),
                null,
                null,
                "Batch update to avoid long transaction.",
                "Batch update to avoid long transaction.",
                "advice_only",
                "UPDATE orders SET status = ? WHERE id = ?"));

        SqlCaptureReporter reporter = new SqlCaptureReporter(properties, new OkHttpClient());
        SqlSentryMyBatisInterceptor interceptor = new SqlSentryMyBatisInterceptor(properties, localCache, reporter);

        BoundSql boundSql = new BoundSql(
                new Configuration(),
                "UPDATE orders SET status = ? WHERE id = ?",
                Collections.<ParameterMapping>emptyList(),
                null);
        MappedStatement mappedStatement = new MappedStatement.Builder(
                new Configuration(),
                AnnotatedMapper.class.getName() + ".updateStatus",
                new StaticSqlSource(new Configuration(), "UPDATE orders SET status = ? WHERE id = ?"),
                SqlCommandType.UPDATE
        ).build();
        TestStatementHandler statementHandler = new TestStatementHandler(boundSql, mappedStatement);
        Method prepareMethod = StatementHandler.class.getMethod("prepare", Connection.class, Integer.class);
        Invocation invocation = new Invocation(statementHandler, prepareMethod, new Object[]{null, Integer.valueOf(30)});

        PrintStream oldOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            interceptor.intercept(invocation);
        } finally {
            System.setOut(oldOut);
        }

        assertEquals("UPDATE orders SET status = ? WHERE id = ?", boundSql.getSql());
        String console = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(console.contains("[SQL Sentry] Advice:"));
        assertTrue(console.contains("[SQL Sentry] SQL: UPDATE orders SET status = ? WHERE id = ?"));
    }

    @SqlSentry
    private interface AnnotatedMapper {
        void findByStatus(String status);

        void updateStatus(String status, Long id);
    }

    private static final class TestStatementHandler implements StatementHandler {
        private final BoundSql boundSql;
        private final MappedStatement mappedStatement;

        private TestStatementHandler(BoundSql boundSql, MappedStatement mappedStatement) {
            this.boundSql = boundSql;
            this.mappedStatement = mappedStatement;
        }

        @Override
        public Statement prepare(Connection connection, Integer transactionTimeout) {
            return null;
        }

        @Override
        public void parameterize(Statement statement) {
        }

        @Override
        public void batch(Statement statement) {
        }

        @Override
        public int update(Statement statement) {
            return 0;
        }

        @Override
        public <E> java.util.List<E> query(Statement statement, ResultHandler resultHandler) {
            return Collections.emptyList();
        }

        @Override
        public <E> Cursor<E> queryCursor(Statement statement) {
            return null;
        }

        @Override
        public BoundSql getBoundSql() {
            return boundSql;
        }

        public MappedStatement getMappedStatement() {
            return mappedStatement;
        }

        @Override
        public ParameterHandler getParameterHandler() {
            return null;
        }
    }
}
