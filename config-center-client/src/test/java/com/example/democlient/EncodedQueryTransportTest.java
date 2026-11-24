package com.example.democlient;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncodedQueryTransportTest {

    @ParameterizedTest
    @ValueSource(strings = {"plain", "a+b", "a b", "中文", "%", "%2B"})
    void buildersAndTransportDeliverOriginalQueryValues(String value) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        BlockingQueue<Map<String, String>> received = new LinkedBlockingQueue<>();
        server.createContext("/api/", exchange -> {
            var uri = exchange.getRequestURI();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("path", uri.getPath());
            for (String parameter : uri.getRawQuery().split("&")) {
                String[] pair = parameter.split("=", 2);
                fields.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            received.add(fields);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReliableHttp http = new ReliableHttp(2000, 2000, new RetryPolicy(1, 1, 1), new CircuitBreaker(2, 1000));
            http.getWithRetry(DemoRunner.buildConfigUrl(base, value, value), null);
            assertEquals(Map.of("path", "/api/configs", "app", value, "env", value), received.remove());
            http.getWithRetry(DemoRunner.buildWatchUrl(base, value, value, 7, 3), null);
            assertEquals(Map.of("path", "/api/configs/watch", "app", value, "env", value,
                    "sinceVersion", "7", "timeoutSeconds", "3"), received.remove());
            http.getWithRetry(DemoRunner.buildEvaluationUrl(base, value, value, value, value), null);
            assertEquals(Map.of("path", "/api/features/evaluate", "app", value, "env", value,
                    "name", value, "userId", value), received.remove());
        } finally {
            server.stop(0);
        }
    }
}
