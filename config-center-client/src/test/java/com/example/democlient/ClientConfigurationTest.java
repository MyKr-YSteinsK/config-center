package com.example.democlient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class ClientConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void clientBeansAreWiredWithSeparateHttpFetchers() {
        clientContext().run(context -> {
            assertThat(context).hasSingleBean(ConfigClient.class);
            assertThat(context).hasBean("standardHttp");
            assertThat(context).hasBean("watchHttp");
            var timing = context.getBean(WatchSession.Timing.class);
            assertEquals(30_000_000_000L, timing.timeoutNanos());
            assertEquals(1_000_000_000L, timing.retryIntervalNanos());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"recoveryTimeoutSeconds=0", "recoveryTimeoutSeconds=-1",
            "recoveryTimeoutSeconds=9223372037", "recoveryRetryIntervalMs=0", "recoveryRetryIntervalMs=-1",
            "recoveryRetryIntervalMs=9223372036855"})
    void watchRecoverySettingsFailAtStartupWhenInvalid(String setting) {
        clientContext().withPropertyValues("demo.watch." + setting)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(
                            setting.endsWith("9223372037") || setting.endsWith("9223372036855")
                                    ? ArithmeticException.class : IllegalArgumentException.class);
                });
    }

    @Test
    void maximumConvertibleRecoverySettingsAreAccepted() {
        var timing = WatchSession.Timing.fromSettings(9_223_372_036L, 9_223_372_036_854L);

        assertEquals(9_223_372_036_000_000_000L, timing.timeoutNanos());
        assertEquals(9_223_372_036_854_000_000L, timing.retryIntervalNanos());
    }

    private ApplicationContextRunner clientContext() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DemoClientApplication.class)
                .withPropertyValues(
                        "demo.baseUrl=http://localhost:8080",
                        "demo.app=app",
                        "demo.env=dev",
                        "demo.featureName=feature",
                        "demo.userId=user"
                );
    }

    @Test
    void watchReadTimeoutIncludesExplicitServerMargin() {
        int readTimeoutMs = ClientHttpConfiguration.watchReadTimeoutMs(10, 2000);

        assertEquals(12_000, readTimeoutMs);
        assertTrue(readTimeoutMs > 10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "malformed"})
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void previousGenerationIsIsolatedUntilAnOnlineRead(String state) throws Exception {
        Path previous = tempDir.resolve(".config-center-client-cache.json");
        Path legacy = tempDir.resolve(".config-center-demo-client-cache.json");
        Path exact = tempDir.resolve(".config-center-client-cache-exact-v1.json");
        String aliasUrl = "http://127.0.0.1/api/configs?app=Demo&env=dev";
        String previousBody = "{\"code\":0,\"data\":[{\"app\":\"demo\",\"value\":\"old alias\"}]}";
        String exactBody = "{\"code\":0,\"data\":[]}";
        var mapper = new ObjectMapper();
        byte[] oldBytes = mapper.writeValueAsBytes(Map.of(aliasUrl,
                new HttpDiskCache.Entry("W/\"old\"", previousBody)));
        Files.write(previous, oldBytes);
        Files.write(legacy, oldBytes);
        if (!state.equals("missing")) {
            Files.writeString(exact, state.equals("empty") ? "" : "not-json");
        }

        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            var calls = new AtomicInteger();
            HttpFetcher normal = (url, etag) -> {
                assertEquals(aliasUrl, url);
                return switch (calls.incrementAndGet()) {
                    case 1 -> {
                        assertNull(etag);
                        throw new HttpRequestFailedException("offline", 503, true);
                    }
                    case 2 -> {
                        assertNull(etag);
                        yield ResponseEntity.ok().eTag("W/\"exact\"").body(exactBody);
                    }
                    case 3 -> {
                        assertEquals("W/\"exact\"", etag);
                        yield ResponseEntity.status(304).build();
                    }
                    case 4 -> {
                        assertEquals("W/\"exact\"", etag);
                        throw new HttpRequestFailedException("offline", 503, true);
                    }
                    default -> throw new AssertionError("unexpected request");
                };
            };
            var cache = new HttpDiskCache();
            assertNull(cache.get(aliasUrl));
            var client = new ConfigClient(normal, normal, cache, mapper);
            assertThrows(HttpRequestFailedException.class, () -> client.fetchConfigs(aliasUrl));
            assertEquals(exactBody, client.fetchConfigs(aliasUrl).body());
            // Relaunch against the new generation on disk.
            var reloaded = new ConfigClient(normal, normal, new HttpDiskCache(), mapper);
            var notModified = reloaded.fetchConfigs(aliasUrl);
            assertTrue(notModified.notModified());
            assertEquals(exactBody, notModified.body());
            var fallback = reloaded.fetchConfigs(aliasUrl);
            assertTrue(fallback.fromCache());
            assertFalse(fallback.notModified());
            assertEquals(exactBody, fallback.body());
            assertArrayEquals(oldBytes, Files.readAllBytes(previous));
            assertArrayEquals(oldBytes, Files.readAllBytes(legacy));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void atomicMoveUnsupported_fallsBackToSafeReplacement() {
        Path canonical = tempDir.resolve("fallback-cache.json");
        AtomicInteger moveAttempts = new AtomicInteger();
        HttpDiskCache.CacheFileMover mover = (source, target, options) -> {
            if (moveAttempts.incrementAndGet() == 1) {
                throw new AtomicMoveNotSupportedException(
                        source.toString(), target.toString(), "injected");
            }
            return Files.move(source, target, options);
        };

        HttpDiskCache cache = new HttpDiskCache(canonical, mover);
        cache.put("configs", "etag", "body");

        assertEquals(2, moveAttempts.get());
        assertEquals("body", new HttpDiskCache(canonical).get("configs").body);
    }

    @Test
    void failedReplacement_preservesExistingCanonicalCache() throws Exception {
        Path canonical = tempDir.resolve("preserved-cache.json");
        HttpDiskCache initial = new HttpDiskCache(canonical);
        initial.put("configs", "old-etag", "old-body");
        byte[] originalBytes = Files.readAllBytes(canonical);

        HttpDiskCache failing = new HttpDiskCache(canonical,
                (source, target, options) -> {
                    throw new IOException("injected replacement failure");
                });
        failing.put("configs", "new-etag", "new-body");

        assertArrayEquals(originalBytes, Files.readAllBytes(canonical));
        assertEquals("old-body", new HttpDiskCache(canonical).get("configs").body);
        assertFalse(Files.exists(canonical.resolveSibling(canonical.getFileName() + ".tmp")));
    }

    @Test
    void concurrentWrites_leaveOneCompleteReadableCache() throws Exception {
        Path canonical = tempDir.resolve("concurrent-cache.json");
        HttpDiskCache cache = new HttpDiskCache(canonical);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Callable<Void>> writes = IntStream.range(0, 20)
                .mapToObj(index -> (Callable<Void>) () -> {
                    cache.put("url-" + index, "etag-" + index, "body-" + index);
                    return null;
                })
                .toList();

        try {
            for (Future<Void> result : executor.invokeAll(writes)) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }

        HttpDiskCache reloaded = new HttpDiskCache(canonical);
        for (int index = 0; index < 20; index++) {
            assertEquals("body-" + index, reloaded.get("url-" + index).body);
        }
    }
}
