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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class LoopbackWriteFixture {

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final int port;
    private final WatchRequests watchRequests;

    LoopbackWriteFixture(ObjectMapper json, int port, WatchRequests watchRequests) {
        this.json = json;
        this.port = port;
        this.watchRequests = watchRequests;
    }

    Call post(String path, String key, String trace, Map<String, ?> fields) throws Exception {
        return await(postAsync(path, key, trace, fields));
    }

    CompletableFuture<Call> postAsync(String path, String key, String trace, Map<String, ?> fields) throws Exception {
        return sendPost(path, key, trace, json.writeValueAsBytes(fields));
    }

    Call postJson(String path, String key, String trace, String body) throws Exception {
        return await(sendPost(path, key, trace, body.getBytes(StandardCharsets.UTF_8)));
    }

    private CompletableFuture<Call> sendPost(String path, String key, String trace, byte[] body) {
        return send(request(path).header(TraceIdFilter.HEADER, trace).header("X-API-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    Call get(String path) throws Exception {
        return await(send(request(path).GET().build()));
    }

    Watch watch(String app, String env, long since, int timeout, String trace) throws Exception {
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
                                json.readTree(response.body()));
                    } catch (Exception cause) {
                        throw new IllegalStateException("Expected trace and JSON for " + request.uri().getPath(), cause);
                    }
                });
    }

    static Call await(CompletableFuture<Call> future) throws Exception {
        return future.get(17, SECONDS);
    }

    static String scope(String app, String env) {
        return "?app=" + encode(app) + "&env=" + encode(env);
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Call(int status, String trace, JsonNode body) {
        JsonNode expect(int expectedStatus, int code) {
            assertEquals(expectedStatus, status, body::toString);
            assertEquals(code, body.path("code").asInt(), body::toString);
            assertEquals(trace, body.path("traceId").asText());
            return body.path("data");
        }
    }

    record Watch(CompletableFuture<Call> response, String trace) implements AutoCloseable {
        void expect(boolean changed, long revision) throws Exception {
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
    static class WatchRequests implements WebMvcConfigurer {
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
