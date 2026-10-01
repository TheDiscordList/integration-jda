package com.thediscordlist.jda;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A local stand-in for the TheDiscordList API that records requests and replays scripted responses. */
final class StubServer implements AutoCloseable {
    record Request(String method, String path, Headers headers, String body) {
        JsonNode json() throws IOException {
            return ApiClient.JSON.readTree(body);
        }
    }

    record Response(int status, Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Response> script = new ConcurrentLinkedQueue<>();
    private boolean closed;

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));
            Response response = script.poll();
            if (response == null) {
                response = new Response(200, Map.of(), "{\"message\":\"ok\"}");
            }
            response.headers().forEach(exchange.getResponseHeaders()::add);
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
    }

    StubServer respond(int status, String body, String... headerPairs) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < headerPairs.length; i += 2) {
            headers.put(headerPairs[i], headerPairs[i + 1]);
        }
        script.add(new Response(status, headers, body));
        return this;
    }

    Request take() throws InterruptedException {
        Request request = requests.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "expected a request");
        return request;
    }

    void assertQuiet(long millis) throws InterruptedException {
        assertNull(requests.poll(millis, TimeUnit.MILLISECONDS), "expected no request");
    }

    int pending() {
        return requests.size();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            server.stop(0);
        }
    }
}
