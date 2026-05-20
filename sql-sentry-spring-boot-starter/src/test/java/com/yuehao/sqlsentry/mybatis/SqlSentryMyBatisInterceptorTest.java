package com.yuehao.sqlsentry.mybatis;

import com.yuehao.sqlsentry.client.SqlCaptureReporter;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import com.yuehao.sqlsentry.model.SqlRewriteRule;
import com.yuehao.sqlsentry.rewrite.SqlFingerprintUtils;
import com.yuehao.sqlsentry.rewrite.SqlRewriteLocalCache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
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
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlSentryMyBatisInterceptorTest {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String SELECT_MAPPED_STATEMENT_ID = "simulator.DemoOrderMapper.selectByStatus";
    private static final String SELECT_SQL = "SELECT id, status FROM orders WHERE status = ? ORDER BY id";
    private static final String OPTIMIZED_SELECT_SQL = "SELECT id, status FROM orders WHERE status = ? ORDER BY id LIMIT 100";
    private static final String UPDATE_MAPPED_STATEMENT_ID = "simulator.DemoOrderMapper.updateStatus";
    private static final String UPDATE_SQL = "UPDATE orders SET status = ? WHERE id = ?";

    @Test
    void shouldOnlyPrintTwoLinesWhenSlowSqlCanBeAutoReplaced() throws Throwable {
        AtomicReference<Request> capturedRequest = new AtomicReference<Request>();
        PrintStream oldOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            SqlSentryProperties properties = buildProperties();
            SqlRewriteLocalCache localCache = new SqlRewriteLocalCache(properties);
            localCache.put(new SqlRewriteRule(
                    SqlFingerprintUtils.fingerprint(SELECT_SQL),
                    OPTIMIZED_SELECT_SQL,
                    null,
                    "ORDER BY 命中慢 SQL 替换规则",
                    null,
                    "approved",
                    OPTIMIZED_SELECT_SQL));

            SqlCaptureReporter reporter = new SqlCaptureReporter(
                    properties,
                    newMockClient("{"
                            + "\"fingerprint\":\"demo-fingerprint\","
                            + "\"diagnosisStatus\":\"ai_pending\","
                            + "\"headline\":\"\\u4f4e\\uff1a\\u6392\\u5e8f\\u538b\\u529b\","
                            + "\"summary\":\"\\u547d\\u4e2d\\u6392\\u5e8f\\u89c4\\u5219\\u3002\""
                            + "}", capturedRequest));
            SqlSentryMyBatisInterceptor interceptor = new SqlSentryMyBatisInterceptor(properties, localCache, reporter);
            TestStatementHandler statementHandler = new TestStatementHandler(
                    newSelectBoundSql(Integer.valueOf(2)),
                    newSelectMappedStatement());

            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            interceptor.intercept(prepareInvocation(statementHandler));
            interceptor.intercept(queryInvocation(statementHandler));
        } finally {
            System.setOut(oldOut);
        }

        assertCaptureUrl(capturedRequest.get());
        String console = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(console.contains("[SQL Sentry] 结论: 慢sql已被替换"));
        assertTrue(console.contains("[SQL Sentry] 位置: " + SELECT_MAPPED_STATEMENT_ID));
        assertTrue(!console.contains("[SQL Sentry] 示例:"));
        assertTrue(!console.contains("[SQL Sentry] 建议:"));
        assertTrue(!console.contains("风险等级"));
    }

    @Test
    void shouldPrintConclusionExampleAndLocationWhenSlowSqlCannotBeAutoReplaced() throws Throwable {
        AtomicReference<Request> capturedRequest = new AtomicReference<Request>();
        PrintStream oldOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            SqlSentryProperties properties = buildProperties();
            SqlRewriteLocalCache localCache = new SqlRewriteLocalCache(properties);
            SqlCaptureReporter reporter = new SqlCaptureReporter(
                    properties,
                    newMockClient("{"
                            + "\"fingerprint\":\"demo-fingerprint\","
                            + "\"diagnosisStatus\":\"ai_pending\","
                            + "\"headline\":\"\\u4f4e\\uff1a\\u6392\\u5e8f\\u538b\\u529b\","
                            + "\"summary\":\"\\u547d\\u4e2d\\u6392\\u5e8f\\u89c4\\u5219\\u3002\","
                            + "\"advice\":\"\\u68c0\\u67e5 ORDER BY \\u5b57\\u6bb5\\u662f\\u5426\\u80fd\\u547d\\u4e2d\\u8054\\u5408\\u7d22\\u5f15\\u3002\""
                            + "}", capturedRequest));
            SqlSentryMyBatisInterceptor interceptor = new SqlSentryMyBatisInterceptor(properties, localCache, reporter);
            TestStatementHandler statementHandler = new TestStatementHandler(
                    newSelectBoundSql(Integer.valueOf(2)),
                    newSelectMappedStatement());

            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            interceptor.intercept(prepareInvocation(statementHandler));
            interceptor.intercept(queryInvocation(statementHandler));
        } finally {
            System.setOut(oldOut);
        }

        assertCaptureUrl(capturedRequest.get());
        String console = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(console.contains("[SQL Sentry] 结论: 这条 SQL 存在排序开销，当前风险等级为低。"));
        assertTrue(console.contains("[SQL Sentry] 示例: SELECT id, status FROM orders WHERE status = 2 ORDER BY id"));
        assertTrue(console.contains("[SQL Sentry] 位置: " + SELECT_MAPPED_STATEMENT_ID));
        assertTrue(!console.contains("[SQL Sentry] 建议:"));
        assertTrue(!console.contains("status = ?"));
    }

    @Test
    void shouldSendUpdateSqlToServerForNonQueryCapture() throws Throwable {
        AtomicReference<Request> capturedRequest = new AtomicReference<Request>();
        PrintStream oldOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            SqlSentryProperties properties = buildProperties();
            SqlRewriteLocalCache localCache = new SqlRewriteLocalCache(properties);
            SqlCaptureReporter reporter = new SqlCaptureReporter(
                    properties,
                    newMockClient("{"
                            + "\"fingerprint\":\"demo-fingerprint\","
                            + "\"diagnosisStatus\":\"ai_pending\","
                            + "\"headline\":\"\\u4e2d\\uff1a\\u6279\\u91cf\\u66f4\\u65b0\","
                            + "\"summary\":\"\\u547d\\u4e2d\\u975e\\u67e5\\u8be2 SQL \\u68c0\\u6d4b\\u89c4\\u5219\\u3002\""
                            + "}", capturedRequest));
            SqlSentryMyBatisInterceptor interceptor = new SqlSentryMyBatisInterceptor(properties, localCache, reporter);
            TestStatementHandler statementHandler = new TestStatementHandler(
                    newUpdateBoundSql("PAID", Long.valueOf(1001L)),
                    newUpdateMappedStatement());

            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8.name()));
            interceptor.intercept(prepareInvocation(statementHandler));
            interceptor.intercept(updateInvocation(statementHandler));
        } finally {
            System.setOut(oldOut);
        }

        Request request = capturedRequest.get();
        assertCaptureUrl(request);
        assertTrue(readRequestBody(request).contains("\"sql\":\"" + UPDATE_SQL + "\""));
        String console = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(console.contains("[SQL Sentry] 结论: 这条 SQL 存在批量更新，当前风险等级为中。"));
        assertTrue(console.contains("[SQL Sentry] 示例: UPDATE orders SET status = 'PAID' WHERE id = 1001"));
        assertTrue(console.contains("[SQL Sentry] 位置: " + UPDATE_MAPPED_STATEMENT_ID));
        assertTrue(!console.contains("[SQL Sentry] 建议:"));
    }

    private SqlSentryProperties buildProperties() {
        SqlSentryProperties properties = new SqlSentryProperties();
        properties.setEnabled(true);
        properties.setCaptureEnabled(true);
        properties.setRewriteEnabled(true);
        properties.setSlowSqlThresholdMs(0L);
        properties.setServerBaseUrl("http://127.0.0.1:18080");
        assertEquals(SqlSentryProperties.FIXED_SERVER_BASE_URL, properties.getServerBaseUrl());
        return properties;
    }

    private OkHttpClient newMockClient(String responseJson, AtomicReference<Request> capturedRequest) throws IOException {
        OkHttpClient okHttpClient = mock(OkHttpClient.class);
        Call call = mock(Call.class);
        when(okHttpClient.newCall(any(Request.class))).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            capturedRequest.set(request);
            return call;
        });
        doAnswer(invocation -> {
            Callback callback = invocation.getArgument(0);
            Request request = capturedRequest.get();
            Response response = new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(responseJson, JSON_MEDIA_TYPE))
                    .build();
            callback.onResponse(call, response);
            return null;
        }).when(call).enqueue(any(Callback.class));
        return okHttpClient;
    }

    private void assertCaptureUrl(Request request) {
        assertNotNull(request);
        assertEquals(
                SqlSentryProperties.FIXED_SERVER_BASE_URL + "/api/sql/captures",
                request.url().toString());
    }

    private String readRequestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
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
