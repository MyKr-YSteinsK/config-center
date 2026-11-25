package com.example.configcenter;

import com.example.configcenter.config.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class LoopbackWriteFixture {

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final int port;
    private final WatchRequests watchRequests;

    public LoopbackWriteFixture(ObjectMapper json, int port, WatchRequests watchRequests) {
        this.json = json;
        this.port = port;
        this.watchRequests = watchRequests;
    }

    public Call post(String path, String key, String trace, Map<String, ?> fields, String... receiptKeys) throws Exception {
        return await(postAsync(path, key, trace, fields, receiptKeys));
    }

    public CompletableFuture<Call> postAsync(String path, String key, String trace, Map<String, ?> fields,
                                             String... receiptKeys) throws Exception {
        return sendPost(path, key, trace, json.writeValueAsBytes(fields), receiptKeys);
    }

    public Call postJson(String path, String key, String trace, String body, String... receiptKeys) throws Exception {
        return await(sendPost(path, key, trace, body.getBytes(StandardCharsets.UTF_8), receiptKeys));
    }

    private CompletableFuture<Call> sendPost(String path, String key, String trace, byte[] body, String[] receiptKeys) {
        var builder = request(path).header(TraceIdFilter.HEADER, trace).header("Content-Type", "application/json");
        if (key != null) {
            builder.header("X-API-Key", key);
        }
        for (String receiptKey : receiptKeys) {
            builder.header("Idempotency-Key", receiptKey);
        }
        return send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    public Call get(String path) throws Exception {
        return get(path, Map.of());
    }

    public Call get(String path, Map<String, String> headers) throws Exception {
        var builder = request(path).GET();
        headers.forEach(builder::header);
        return await(send(builder.build()));
    }

    public Watch watch(String app, String env, long since, int timeout, String trace) throws Exception {
        var parked = new CompletableFuture<Void>();
        watchRequests.parked.put(trace, parked);
        var response = send(request("/api/configs/watch" + scope(app, env)
                + "&sinceVersion=" + since + "&timeoutSeconds=" + timeout)
                .header(TraceIdFilter.HEADER, trace).GET().build());
        try {
            parked.get(7, SECONDS);
            return new Watch(response, trace);
        } catch (Exception failure) {
            response.cancel(true);
            throw failure;
        } finally {
            watchRequests.parked.remove(trace);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
    }

    private CompletableFuture<Call> send(HttpRequest request) {
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    try {
                        return new Call(response.statusCode(),
                                response.headers().firstValue(TraceIdFilter.HEADER).orElseThrow(),
                                json.readTree(response.body()), response.headers());
                    } catch (Exception cause) {
                        throw new IllegalStateException("Expected trace and JSON for " + request.uri().getPath(), cause);
                    }
                });
    }

    public static Call await(CompletableFuture<Call> future) throws Exception {
        return future.get(17, SECONDS);
    }

    public static String scope(String app, String env) {
        return "?app=" + encode(app) + "&env=" + encode(env);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Call(int status, String trace, JsonNode body, HttpHeaders headers) {
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public JsonNode expect(int expectedStatus, int code) {
            assertEquals(expectedStatus, status, body::toString);
            assertEquals(code, body.path("code").asInt(), body::toString);
            assertEquals(trace, body.path("traceId").asText());
            return body.path("data");
        }
    }

    public record Watch(CompletableFuture<Call> response, String trace) implements AutoCloseable {
        public void expect(boolean changed, long revision) throws Exception {
            var call = await(response);
            var data = call.expect(200, 0);
            assertEquals(trace, call.trace());
            assertEquals(changed, data.path("changed").booleanValue());
            assertEquals(revision, data.path("latestVersion").longValue());
        }

        @Override
        public void close() {
            response.cancel(true);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class WatchRequests implements WebMvcConfigurer {
        private final Map<String, CompletableFuture<Void>> parked = new ConcurrentHashMap<>();

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new AsyncHandlerInterceptor() {
                @Override
                public void afterConcurrentHandlingStarted(
                        HttpServletRequest request, HttpServletResponse response, Object handler) {
                    String trace = request.getHeader(TraceIdFilter.HEADER);
                    if (trace != null) {
                        var signal = parked.get(trace);
                        if (signal != null) {
                            signal.complete(null);
                        }
                    }
                }
            }).addPathPatterns("/api/configs/watch");
        }
    }
}
