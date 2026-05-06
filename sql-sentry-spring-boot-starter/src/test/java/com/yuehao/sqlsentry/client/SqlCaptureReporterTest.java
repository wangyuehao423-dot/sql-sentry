package com.yuehao.sqlsentry.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCaptureReporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldIncludeAiConfigInCapturePayload() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/sql/captures", exchange -> handleCapture(exchange, requestBody, latch));
        server.start();

        try {
            SqlSentryProperties properties = new SqlSentryProperties();
            properties.setEnabled(true);
            properties.setCaptureEnabled(true);
            properties.setServerBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setSource("demo-service");
            properties.setDatabase("demo_db");
            properties.getAi().setModel("kimi-k2.5");
            properties.getAi().setApiUrl("https://api.example.com/v1/chat/completions");
            properties.getAi().setApiKey("test-api-key");

            SqlCaptureReporter reporter = new SqlCaptureReporter(properties, new OkHttpClient());
            reporter.reportSlowSql("SELECT * FROM orders WHERE status = ?", 789L);

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            JsonNode payload = objectMapper.readTree(requestBody.get());
            assertEquals("kimi-k2.5", payload.path("model").asText());
            assertEquals("https://api.example.com/v1/chat/completions", payload.path("apiUrl").asText());
            assertEquals("test-api-key", payload.path("apiKey").asText());
            assertEquals("demo-service", payload.path("source").asText());
            assertEquals("demo_db", payload.path("database").asText());
        } finally {
            server.stop(0);
        }
    }

    private void handleCapture(HttpExchange exchange, AtomicReference<String> requestBody, CountDownLatch latch)
            throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            requestBody.set(readUtf8(inputStream));
        } finally {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            latch.countDown();
        }
    }

    private String readUtf8(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }
}
