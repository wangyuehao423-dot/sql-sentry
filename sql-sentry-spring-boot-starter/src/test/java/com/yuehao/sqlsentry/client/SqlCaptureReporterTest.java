package com.yuehao.sqlsentry.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlCaptureReporterTest {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldIncludeAiConfigInCapturePayload() throws Exception {
        AtomicReference<Request> capturedRequest = new AtomicReference<Request>();
        SqlCaptureReporter reporter = new SqlCaptureReporter(
                buildProperties(),
                newMockClient("{}", capturedRequest));

        reporter.reportSlowSql("SELECT * FROM orders WHERE status = ?", 789L);

        Request request = capturedRequest.get();
        assertNotNull(request);
        assertEquals(
                SqlSentryProperties.FIXED_SERVER_BASE_URL + "/api/sql/captures",
                request.url().toString());

        JsonNode payload = objectMapper.readTree(readRequestBody(request));
        assertEquals("kimi-k2.5", payload.path("model").asText());
        assertEquals("https://api.example.com/v1/chat/completions", payload.path("apiUrl").asText());
        assertEquals("test-api-key", payload.path("apiKey").asText());
        assertEquals("demo-service", payload.path("source").asText());
        assertEquals("demo_db", payload.path("database").asText());
    }

    private SqlSentryProperties buildProperties() {
        SqlSentryProperties properties = new SqlSentryProperties();
        properties.setEnabled(true);
        properties.setCaptureEnabled(true);
        properties.setServerBaseUrl("http://127.0.0.1:18080");
        properties.setSource("demo-service");
        properties.setDatabase("demo_db");
        properties.getAi().setModel("kimi-k2.5");
        properties.getAi().setApiUrl("https://api.example.com/v1/chat/completions");
        properties.getAi().setApiKey("test-api-key");
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
                    .code(202)
                    .message("Accepted")
                    .body(ResponseBody.create(responseJson, JSON_MEDIA_TYPE))
                    .build();
            callback.onResponse(call, response);
            return null;
        }).when(call).enqueue(any(Callback.class));
        return okHttpClient;
    }

    private String readRequestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }
}
