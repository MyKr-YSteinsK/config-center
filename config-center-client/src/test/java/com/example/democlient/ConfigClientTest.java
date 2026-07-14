package com.example.democlient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigClientTest {

    @Test
    void etag304_usesExistingCache() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "W/\"etag\"", "cached-body");
        AtomicReference<String> receivedEtag = new AtomicReference<>();
        HttpFetcher standard = (url, etag) -> {
            receivedEtag.set(etag);
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        };

        ConfigClient.FetchResult result = client(standard, unusedWatch(), cache).fetchConfigs("configs");

        assertEquals("W/\"etag\"", receivedEtag.get());
        assertEquals("cached-body", result.body());
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
        cache.put("configs", "etag", "cached-body");
        HttpFetcher standard = (url, etag) -> {
            throw new HttpRequestFailedException("HTTP_500", 500, true);
        };

        ConfigClient.FetchResult result = client(standard, unusedWatch(), cache).fetchConfigs("configs");

        assertEquals("cached-body", result.body());
        assertTrue(result.fromCache());
        assertFalse(result.notModified());
    }

    @Test
    void nonRetryableError_isNotCachedOrHiddenByFallback() {
        InMemoryCache cache = new InMemoryCache();
        cache.put("configs", "etag", "old-body");
        int writesBefore = cache.putCount;
        HttpFetcher standard = (url, etag) -> {
            throw new HttpRequestFailedException("HTTP_403", 403, false);
        };

        assertThrows(HttpRequestFailedException.class,
                () -> client(standard, unusedWatch(), cache).fetchConfigs("configs"));
        assertEquals(writesBefore, cache.putCount);
        assertEquals("old-body", cache.get("configs").body);
    }

    @Test
    void changedWatch_refetchesAndPersistsLatestConfig() throws Exception {
        InMemoryCache cache = new InMemoryCache();
        AtomicInteger fetches = new AtomicInteger();
        HttpFetcher standard = (url, etag) -> {
            fetches.incrementAndGet();
            HttpHeaders headers = new HttpHeaders();
            headers.setETag("W/\"new\"");
            return new ResponseEntity<>("new-body", headers, HttpStatus.OK);
        };
        HttpFetcher watch = (url, etag) -> ResponseEntity.ok(
                "{\"data\":{\"changed\":true,\"latestVersion\":7}}");

        ConfigClient.WatchResult result = client(standard, watch, cache)
                .watchOnce("watch", 6, "configs");

        assertTrue(result.changed());
        assertEquals(7, result.latestVersion());
        assertEquals(1, fetches.get());
        assertNotNull(result.refreshed());
        assertEquals("new-body", cache.get("configs").body);
    }

    private ConfigClient client(HttpFetcher standard, HttpFetcher watch, ConfigCache cache) {
        return new ConfigClient(standard, watch, cache, new ObjectMapper());
    }

    private HttpFetcher unusedWatch() {
        return (url, etag) -> {
            throw new AssertionError("watch should not be called");
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
