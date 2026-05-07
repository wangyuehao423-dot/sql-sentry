package com.yuehao.sqlsentry.mybatis;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSentryMyBatisInterceptorTest {

    private static final String SELECT_MAPPED_STATEMENT_ID = "simulator.DemoOrderMapper.selectByStatus";
    private static final String SELECT_SQL = "SELECT id, status FROM orders WHERE status = ? ORDER BY id";
    private static final String OPTIMIZED_SELECT_SQL = "SELECT id, status FROM orders WHERE status = ? ORDER BY id LIMIT 100";
    private static final String UPDATE_MAPPED_STATEMENT_ID = "simulator.DemoOrderMapper.updateStatus";
    private static final String UPDATE_SQL = "UPDATE orders SET status = ? WHERE id = ?";
    @Test
    void shouldOnlyPrintTwoLinesWhenSlowSqlCanBeAutoReplaced() throws Throwable {
        CountDownLatch captureLatch = new CountDownLatch(1);
        HttpServer server = createCaptureServer("{"
                + "\"fingerprint\":\"demo-fingerprint\","
                + "\"diagnosisStatus\":\"ai_pending\","
                + "\"headline\":\"\\u4f4e\\uff1a\\u6392\\u5e8f\\u538b\\u529b\","
                + "\"summary\":\"\\u547d\\u4e2d\\u6392\\u5e8f\\u89c4\\u5219\\u3002\""
                + "}", captureLatch);
        server.start();

        PrintStream oldOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            SqlSentryProperties properties = buildProperties(server);
            SqlRewriteLocalCache localCache = new SqlRewriteLocalCache(properties);
            localCache.put(new SqlRewriteRule(
                    SqlFingerprintUtils.fingerprint(SELECT_SQL),
                    OPTIMIZED_SELECT_SQL,
                    null,
                    "ORDER BY \u547d\u4e2d\u6162 SQL \u66ff\u6362\u89c4\u5219",
                    null,
                    "approved",
                    OPTIMIZED_SELECT_SQL));

            SqlCaptureReporter reporter = new SqlCaptureReporter(properties, new OkHttpClient());
            SqlSentryMyBatisInterceptor interceptor = new SqlSentryMyBatisInterceptor(properties, localCache, reporter);
            TestStatementHandler statementHandler = new TestStatementHandler(
                    newSelectBoundSql(Integer.valueOf(2)),
                    newSelectMappedStatement());

            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            interceptor.intercept(prepareInvocation(statementHandler));
            interceptor.intercept(queryInvocation(statementHandler));

            assertTrue(captureLatch.await(5, TimeUnit.SECONDS));
            Thread.sleep(200L);
        } finally {
            System.setOut(oldOut);
            server.stop(0);
        }

        String console = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(console.contains("[SQL Sentry] \u7ed3\u8bba: \u6162sql\u5df2\u88ab\u66ff\u6362"));
        assertTrue(console.contains("[SQL Sentry] \u4f4d\u7f6e: " + SELECT_MAPPED_STATEMENT_ID));
        assertTrue(!console.contains("[SQL Sentry] \u793a\u4f8b:"));
        assertTrue(!console.contains("[SQL Sentry] \u5efa\u8bae:"));
        assertTrue(!console.contains("\u98ce\u9669\u7b49\u7ea7"));
    }

    @Test
    void shouldPrintConclusionExampleAndLocationWhenSlowSqlCannotBeAutoReplaced() throws Throwable {
        CountDownLatch captureLatch = new CountDownLatch(1);
        HttpServer server = createCaptureServer("{"
                + "\"fingerprint\":\"demo-fingerprint\","
                + "\"diagnosisStatus\":\"ai_pending\","
                + "\"headline\":\"\\u4f4e\\uff1a\\u6392\\u5e8f\\u538b\\u529b\","
                + "\"summary\":\"\\u547d\\u4e2d\\u6392\\u5e8f\\u89c4\\u5219\\u3002\","
                + "\"advice\":\"\\u68c0\\u67e5 ORDER BY \\u5b57\\u6bb5\\u662f\\u5426\\u80fd\\u547d\\u4e2d\\u8054\\u5408\\u7d22\\u5f15\\u3002\""
                + "}", captureLatch);
        server.start();

        PrintStream oldOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            SqlSentryProperties properties = buildProperties(server);
            SqlRewriteLocalCache localCache = new SqlRewriteLocalCache(properties);
            SqlCaptureReporter reporter = new SqlCaptureReporter(properties, new OkHttpClient());
            SqlSentryMyBatisInterceptor interceptor = new SqlSentryMyBatisInterceptor(properties, localCache, reporter);
            TestStatementHandler statementHandler = new TestStatementHandler(
                    newSelectBoundSql(Integer.valueOf(2)),
                    newSelectMappedStatement());

            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            interceptor.intercept(prepareInvocation(statementHandler));
            interceptor.intercept(queryInvocation(statementHandler));

            assertTrue(captureLatch.await(5, TimeUnit.SECONDS));
            waitForConsole(output, "[SQL Sentry] \u4f4d\u7f6e: " + SELECT_MAPPED_STATEMENT_ID);
        } finally {
            System.setOut(oldOut);
            server.stop(0);
        }

        String console = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(console.contains("[SQL Sentry] \u7ed3\u8bba: \u8fd9\u6761 SQL \u5b58\u5728\u6392\u5e8f\u5f00\u9500\uff0c\u5f53\u524d\u98ce\u9669\u7b49\u7ea7\u4e3a\u4f4e\u3002"));
        assertTrue(console.contains("[SQL Sentry] \u793a\u4f8b: SELECT id, status FROM orders WHERE status = 2 ORDER BY id"));
        assertTrue(console.contains("[SQL Sentry] \u4f4d\u7f6e: " + SELECT_MAPPED_STATEMENT_ID));
        assertTrue(!console.contains("[SQL Sentry] \u5efa\u8bae:"));
        assertTrue(!console.contains("status = ?"));
    }

    @Test
    void shouldSendUpdateSqlToServerForNonQueryCapture() throws Throwable {
        CountDownLatch captureLatch = new CountDownLatch(1);
        AtomicReference<String> requestBody = new AtomicReference<String>();
        HttpServer server = createCaptureServer("{"
                + "\"fingerprint\":\"demo-fingerprint\","
                + "\"diagnosisStatus\":\"ai_pending\","
                + "\"headline\":\"\\u4e2d\\uff1a\\u6279\\u91cf\\u66f4\\u65b0\","
                + "\"summary\":\"\\u547d\\u4e2d\\u975e\\u67e5\\u8be2 SQL \\u68c0\\u6d4b\\u89c4\\u5219\\u3002\""
                + "}", captureLatch, requestBody);
        server.start();

        PrintStream oldOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            SqlSentryProperties properties = buildProperties(server);
            SqlRewriteLocalCache localCache = new SqlRewriteLocalCache(properties);
            SqlCaptureReporter reporter = new SqlCaptureReporter(properties, new OkHttpClient());
            SqlSentryMyBatisInterceptor interceptor = new SqlSentryMyBatisInterceptor(properties, localCache, reporter);
            TestStatementHandler statementHandler = new TestStatementHandler(
                    newUpdateBoundSql("PAID", Long.valueOf(1001L)),
                    newUpdateMappedStatement());

            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            interceptor.intercept(prepareInvocation(statementHandler));
            interceptor.intercept(updateInvocation(statementHandler));

            assertTrue(captureLatch.await(5, TimeUnit.SECONDS));
            waitForConsole(output, "[SQL Sentry] \u4f4d\u7f6e: " + UPDATE_MAPPED_STATEMENT_ID);
        } finally {
            System.setOut(oldOut);
            server.stop(0);
        }

        assertTrue(requestBody.get().contains("\"sql\":\"" + UPDATE_SQL + "\""));
        String console = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(console.contains("[SQL Sentry] \u7ed3\u8bba: \u8fd9\u6761 SQL \u5b58\u5728\u6279\u91cf\u66f4\u65b0\uff0c\u5f53\u524d\u98ce\u9669\u7b49\u7ea7\u4e3a\u4e2d\u3002"));
        assertTrue(console.contains("[SQL Sentry] \u793a\u4f8b: UPDATE orders SET status = 'PAID' WHERE id = 1001"));
        assertTrue(console.contains("[SQL Sentry] \u4f4d\u7f6e: " + UPDATE_MAPPED_STATEMENT_ID));
        assertTrue(!console.contains("[SQL Sentry] \u5efa\u8bae:"));
    }

    private SqlSentryProperties buildProperties(HttpServer server) {
        SqlSentryProperties properties = new SqlSentryProperties();
        properties.setEnabled(true);
        properties.setCaptureEnabled(true);
        properties.setRewriteEnabled(true);
        properties.setSlowSqlThresholdMs(0L);
        properties.setServerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return properties;
    }

    private HttpServer createCaptureServer(String responseJson, CountDownLatch latch) throws IOException {
        return createCaptureServer(responseJson, latch, null);
    }

    private HttpServer createCaptureServer(
            String responseJson,
            CountDownLatch latch,
            AtomicReference<String> requestBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/sql/captures", exchange -> writeJsonResponse(exchange, responseJson, latch, requestBody));
        return server;
    }

    private void writeJsonResponse(
            HttpExchange exchange,
            String responseJson,
            CountDownLatch latch,
            AtomicReference<String> requestBody) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            String body = readUtf8(inputStream);
            if (requestBody != null) {
                requestBody.set(body);
            }
        } finally {
            byte[] responseBody = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
            latch.countDown();
        }
    }

    private String readUtf8(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private BoundSql newSelectBoundSql(Integer status) {
        Configuration configuration = new Configuration();
        List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
        parameterMappings.add(new ParameterMapping.Builder(configuration, "status", Integer.class).build());

        Map<String, Object> parameterObject = new HashMap<String, Object>();
        parameterObject.put("status", status);
        return new BoundSql(configuration, SELECT_SQL, parameterMappings, parameterObject);
    }

    private BoundSql newUpdateBoundSql(String status, Long id) {
        Configuration configuration = new Configuration();
        List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
        parameterMappings.add(new ParameterMapping.Builder(configuration, "status", String.class).build());
        parameterMappings.add(new ParameterMapping.Builder(configuration, "id", Long.class).build());

        Map<String, Object> parameterObject = new HashMap<String, Object>();
        parameterObject.put("status", status);
        parameterObject.put("id", id);
        return new BoundSql(configuration, UPDATE_SQL, parameterMappings, parameterObject);
    }

    private MappedStatement newSelectMappedStatement() {
        Configuration configuration = new Configuration();
        return new MappedStatement.Builder(
                configuration,
                SELECT_MAPPED_STATEMENT_ID,
                new StaticSqlSource(configuration, SELECT_SQL),
                SqlCommandType.SELECT
        ).build();
    }

    private MappedStatement newUpdateMappedStatement() {
        Configuration configuration = new Configuration();
        return new MappedStatement.Builder(
                configuration,
                UPDATE_MAPPED_STATEMENT_ID,
                new StaticSqlSource(configuration, UPDATE_SQL),
                SqlCommandType.UPDATE
        ).build();
    }

    private Invocation prepareInvocation(TestStatementHandler statementHandler) throws NoSuchMethodException {
        Method prepareMethod = StatementHandler.class.getMethod("prepare", Connection.class, Integer.class);
        return new Invocation(statementHandler, prepareMethod, new Object[]{null, Integer.valueOf(30)});
    }

    private Invocation queryInvocation(TestStatementHandler statementHandler) throws NoSuchMethodException {
        Method queryMethod = StatementHandler.class.getMethod("query", Statement.class, ResultHandler.class);
        return new Invocation(statementHandler, queryMethod, new Object[]{null, null});
    }

    private Invocation updateInvocation(TestStatementHandler statementHandler) throws NoSuchMethodException {
        Method updateMethod = StatementHandler.class.getMethod("update", Statement.class);
        return new Invocation(statementHandler, updateMethod, new Object[]{null});
    }

    private void waitForConsole(ByteArrayOutputStream output, String expectedText) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String console = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (console.contains(expectedText)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Expected console to contain: " + expectedText
                + ", actual output: " + new String(output.toByteArray(), StandardCharsets.UTF_8));
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
        public <E> List<E> query(Statement statement, ResultHandler resultHandler) {
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
