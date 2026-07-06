package com.zhulikang.aimatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class MockAiServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String reportContent;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private MockAiServer(HttpServer server, ExecutorService executor, String reportContent) {
        this.server = server;
        this.executor = executor;
        this.reportContent = reportContent;
    }

    static MockAiServer start(String reportContent) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            MockAiServer mockAiServer = new MockAiServer(server, executor, reportContent);
            server.createContext("/v1/chat/completions", mockAiServer::handleCompletion);
            server.setExecutor(executor);
            server.start();
            return mockAiServer;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    int requestCount() {
        return requestCount.get();
    }

    String lastAuthorization() {
        return lastAuthorization.get();
    }

    String lastBody() {
        return lastBody.get();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleCompletion(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, new byte[0]);
            return;
        }

        requestCount.incrementAndGet();
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        byte[] response = objectMapper.writeValueAsBytes(Map.of(
            "choices",
            List.of(Map.of("message", Map.of("content", reportContent)))
        ));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        send(exchange, 200, response);
    }

    private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; var responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }
}
