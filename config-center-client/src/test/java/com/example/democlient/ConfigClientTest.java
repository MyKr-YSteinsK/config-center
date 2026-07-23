package com.example.democlient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigClientTest {

    private static final String VALID_CONFIG_BODY = "{\"code\":0,\"data\":[]}";

    @Test
    void etag304_usesExistingCache() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "W/\"etag\"", VALID_CONFIG_BODY);
        AtomicReference<String> receivedEtag = new AtomicReference<>();
        HttpFetcher standard = (url, etag) -> {
            receivedEtag.set(etag);
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        };

        ConfigClient.FetchResult result = client(standard, unusedWatch(), cache).fetchConfigs("configs");

        assertEquals("W/\"etag\"", receivedEtag.get());
        assertEquals(VALID_CONFIG_BODY, result.body());
        assertTrue(result.fromCache());
        assertTrue(result.notModified());
    }

    @Test
    void response304_requiresValidCache() {
        HttpFetcher standard = (url, etag) -> ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();

        assertThrows(HttpRequestFailedException.class,
                () -> client(standard, unusedWatch(), new InMemoryCache()).fetchConfigs("configs"));
    }

    @Test
    void exhaustedTransientFailure_fallsBackToCache() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "etag", VALID_CONFIG_BODY);
        HttpFetcher standard = (url, etag) -> {
            throw new HttpRequestFailedException("HTTP_500", 500, true);
        };

        ConfigClient.FetchResult result = client(standard, unusedWatch(), cache).fetchConfigs("configs");

        assertEquals(VALID_CONFIG_BODY, result.body());
        assertTrue(result.fromCache());
        assertFalse(result.notModified());
    }

    @Test
    void malformedCacheIsNotUsedAsTransientFailureFallback() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "etag", "not-json");
        AtomicReference<String> receivedEtag = new AtomicReference<>();
        HttpFetcher standard = (url, etag) -> {
            receivedEtag.set(etag);
            throw new HttpRequestFailedException("HTTP_500", 500, true);
        };

        assertThrows(HttpRequestFailedException.class,
                () -> client(standard, unusedWatch(), cache).fetchConfigs("configs"));
        assertEquals(null, receivedEtag.get());
    }

    @Test
    void malformedCacheDoesNotSendEtagAndCanBeReplacedByFreshResponse() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "W/\"stale\"", "not-json");
        AtomicReference<String> receivedEtag = new AtomicReference<>();
        HttpFetcher standard = (url, etag) -> {
            receivedEtag.set(etag);
            return ResponseEntity.ok(VALID_CONFIG_BODY);
        };

        ConfigClient.FetchResult result = client(standard, unusedWatch(), cache).fetchConfigs("configs");

        assertEquals(null, receivedEtag.get());
        assertFalse(result.fromCache());
        assertEquals(VALID_CONFIG_BODY, cache.get("configs").body);
    }

    @Test
    void malformedCacheWith304FailsAsWithoutValidCache() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "W/\"stale\"", "{\"code\":0,\"data\":{}}");
        AtomicReference<String> receivedEtag = new AtomicReference<>();
        HttpFetcher standard = (url, etag) -> {
            receivedEtag.set(etag);
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        };

        HttpRequestFailedException error = assertThrows(HttpRequestFailedException.class,
                () -> client(standard, unusedWatch(), cache).fetchConfigs("configs"));

        assertEquals(null, receivedEtag.get());
        assertEquals("HTTP_304_WITHOUT_VALID_CACHE", error.getMessage());
        assertFalse(error.isCacheFallbackAllowed());
    }

    @Test
    void validCacheWithMissingOrBlankEtagUsesUnconditionalRequestAndFallback() throws Exception {
        for (String etag : new String[]{null, "   "}) {
            InMemoryCache cache = new InMemoryCache();
            cache.put("configs", etag, VALID_CONFIG_BODY);
            AtomicReference<String> receivedEtag = new AtomicReference<>();
            HttpFetcher standard = (url, requestEtag) -> {
                receivedEtag.set(requestEtag);
                throw new HttpRequestFailedException("HTTP_500", 500, true);
            };

            ConfigClient.FetchResult result = client(standard, unusedWatch(), cache)
                    .fetchConfigs("configs");

            assertEquals(null, receivedEtag.get());
            assertEquals(VALID_CONFIG_BODY, result.body());
            assertTrue(result.fromCache());
        }
    }

    @Test
    void nonRetryableError_isNotCachedOrHiddenByFallback() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "etag", VALID_CONFIG_BODY);
        int writesBefore = cache.putCount;
        HttpFetcher standard = (url, etag) -> {
            throw new HttpRequestFailedException("HTTP_403", 403, false);
        };

        assertThrows(HttpRequestFailedException.class,
                () -> client(standard, unusedWatch(), cache).fetchConfigs("configs"));
        assertEquals(writesBefore, cache.putCount);
        assertEquals(VALID_CONFIG_BODY, cache.get("configs").body);
    }

    @Test
    void repeated429_neverReturnsStaleCacheAsSuccess() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "etag", VALID_CONFIG_BODY);
        int writesBefore = cache.putCount;
        AtomicInteger requests = new AtomicInteger();
        HttpFetcher standard = (url, etag) -> {
            requests.incrementAndGet();
            throw new HttpRequestFailedException("HTTP_429_TOO_MANY_REQUESTS", 429, false);
        };
        ConfigClient client = client(standard, unusedWatch(), cache);

        for (int call = 0; call < 3; call++) {
            assertThrows(HttpRequestFailedException.class,
                    () -> client.fetchConfigs("configs"));
        }

        assertEquals(3, requests.get());
        assertEquals(writesBefore, cache.putCount);
        assertEquals(VALID_CONFIG_BODY, cache.get("configs").body);
    }

    @Test
    void changedWatch_refetchesAndPersistsLatestConfig() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        AtomicInteger fetches = new AtomicInteger();
        String configBody = "{\"code\":0,\"data\":[]}";
        HttpFetcher standard = (url, etag) -> {
            fetches.incrementAndGet();
            HttpHeaders headers = new HttpHeaders();
            headers.setETag("W/\"new\"");
            return new ResponseEntity<>(configBody, headers, HttpStatus.OK);
        };
        HttpFetcher watch = (url, etag) -> ResponseEntity.ok(
                "{\"code\":0,\"data\":{\"changed\":true,\"latestVersion\":7}}");

        ConfigClient.WatchResult result = client(standard, watch, cache)
                .watchOnce("watch", "configs");

        assertTrue(result.changed());
        assertEquals(7, result.latestVersion());
        assertEquals(1, fetches.get());
        assertNotNull(result.refreshed());
        assertEquals(configBody, cache.get("configs").body);
    }

    @Test
    void specialCharacters_areEncodedInAllClientUrls() {
        String configUrl = DemoRunner.buildConfigUrl(
                "http://localhost:8080", "team & api", "dev+blue");
        String watchUrl = DemoRunner.buildWatchUrl(
                "http://localhost:8080", "team & api", "dev+blue", 7, 10);
        String evaluationUrl = DemoRunner.buildEvaluationUrl(
                "http://localhost:8080", "team & api", "dev+blue", "flag/beta", "user?one");

        assertEquals("http://localhost:8080/api/configs?app=team%20%26%20api&env=dev%2Bblue",
                configUrl);
        assertEquals("http://localhost:8080/api/configs/watch?app=team%20%26%20api&env=dev%2Bblue"
                + "&sinceVersion=7&timeoutSeconds=10", watchUrl);
        assertEquals("http://localhost:8080/api/features/evaluate?app=team%20%26%20api"
                + "&env=dev%2Bblue&name=flag%2Fbeta&userId=user%3Fone", evaluationUrl);
    }

    @Test
    void malformedConfig200_isProtocolErrorAndIsNotCached() {
        List<String> invalidBodies = List.of(
                "not-json",
                "{}",
                "{\"code\":\"0\",\"data\":[]}",
                "{\"code\":1,\"data\":[]}",
                "{\"code\":4294967296,\"data\":[]}",
                "{\"code\":0}",
                "{\"code\":0,\"data\":{}}"
        );

        for (String body : invalidBodies) {
            InMemoryCache cache = new InMemoryCache();
            HttpFetcher standard = (url, etag) -> ResponseEntity.ok(body);

            HttpRequestFailedException error = assertThrows(HttpRequestFailedException.class,
                    () -> client(standard, unusedWatch(), cache).fetchConfigs("configs"));

            assertEquals(200, error.getStatusCode());
            assertFalse(error.isCacheFallbackAllowed());
            assertEquals(0, cache.putCount);
        }
    }

    @Test
    void malformedWatch200_requiresBooleanAndIntegralFields() {
        List<String> invalidBodies = List.of(
                "not-json",
                "{\"code\":0,\"data\":{}}",
                "{\"code\":0,\"data\":{\"changed\":\"false\",\"latestVersion\":7}}",
                "{\"code\":0,\"data\":{\"changed\":false,\"latestVersion\":\"7\"}}",
                "{\"code\":0,\"data\":{\"changed\":false,\"latestVersion\":-1}}"
        );

        for (String body : invalidBodies) {
            HttpFetcher watch = (url, etag) -> ResponseEntity.ok(body);

            HttpRequestFailedException error = assertThrows(HttpRequestFailedException.class,
                    () -> client(unusedStandard(), watch, new InMemoryCache())
                            .watchOnce("watch", "configs"));

            assertEquals(200, error.getStatusCode());
            assertFalse(error.isCacheFallbackAllowed());
        }
    }

    private ConfigClient client(HttpFetcher standard, HttpFetcher watch, ConfigCache cache) {
        return new ConfigClient(standard, watch, cache, new ObjectMapper());
    }

    private HttpFetcher unusedWatch() {
        return (url, etag) -> {
            throw new AssertionError("watch should not be called");
        };
    }

    private HttpFetcher unusedStandard() {
        return (url, etag) -> {
            throw new AssertionError("standard fetch should not be called");
        };
    }

    private static class InMemoryCache implements ConfigCache {
        private final Map<String, HttpDiskCache.Entry> entries = new HashMap<>();
        private int putCount;

        @Override
        public HttpDiskCache.Entry get(String url) {
            return entries.get(url);
        }

        @Override
        public void put(String url, String etag, String body) {
            putCount++;
            entries.put(url, new HttpDiskCache.Entry(etag, body));
        }
    }
}
