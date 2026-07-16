package com.example.democlient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class ClientConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    void clientBeansAreWiredWithSeparateHttpFetchers() {
        new ApplicationContextRunner()
                .withUserConfiguration(DemoClientApplication.class)
                .withPropertyValues(
                        "demo.baseUrl=http://localhost:8080",
                        "demo.app=app",
                        "demo.env=dev",
                        "demo.featureName=feature",
                        "demo.userId=user"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ConfigClient.class);
                    assertThat(context).hasBean("standardHttp");
                    assertThat(context).hasBean("watchHttp");
                });
    }

    @Test
    void watchReadTimeoutIncludesExplicitServerMargin() {
        int readTimeoutMs = ClientHttpConfiguration.watchReadTimeoutMs(10, 2000);

        assertEquals(12_000, readTimeoutMs);
        assertTrue(readTimeoutMs > 10_000);
    }

    @Test
    void legacyCacheIsMigratedToCanonicalFile() throws Exception {
        Path canonical = tempDir.resolve(".config-center-client-cache.json");
        Path legacy = tempDir.resolve(".config-center-demo-client-cache.json");
        ObjectMapper mapper = new ObjectMapper();
        Files.write(legacy, mapper.writeValueAsBytes(Map.of(
                "configs", new HttpDiskCache.Entry("etag", "body")
        )));

        HttpDiskCache cache = new HttpDiskCache(canonical, legacy);

        assertEquals("body", cache.get("configs").body);
        assertTrue(Files.exists(canonical));
    }

    @Test
    void atomicMoveUnsupported_fallsBackToSafeReplacement() {
        Path canonical = tempDir.resolve("fallback-cache.json");
        Path legacy = tempDir.resolve("missing-legacy.json");
        AtomicInteger moveAttempts = new AtomicInteger();
        HttpDiskCache.CacheFileMover mover = (source, target, options) -> {
            if (moveAttempts.incrementAndGet() == 1) {
                throw new AtomicMoveNotSupportedException(
                        source.toString(), target.toString(), "injected");
            }
            return Files.move(source, target, options);
        };

        HttpDiskCache cache = new HttpDiskCache(canonical, legacy, mover);
        cache.put("configs", "etag", "body");

        assertEquals(2, moveAttempts.get());
        assertEquals("body", new HttpDiskCache(canonical, legacy).get("configs").body);
    }

    @Test
    void failedReplacement_preservesExistingCanonicalCache() throws Exception {
        Path canonical = tempDir.resolve("preserved-cache.json");
        Path legacy = tempDir.resolve("missing-preserved-legacy.json");
        HttpDiskCache initial = new HttpDiskCache(canonical, legacy);
        initial.put("configs", "old-etag", "old-body");
        byte[] originalBytes = Files.readAllBytes(canonical);

        HttpDiskCache failing = new HttpDiskCache(canonical, legacy,
                (source, target, options) -> {
                    throw new IOException("injected replacement failure");
                });
        failing.put("configs", "new-etag", "new-body");

        assertArrayEquals(originalBytes, Files.readAllBytes(canonical));
        assertEquals("old-body", new HttpDiskCache(canonical, legacy).get("configs").body);
        assertFalse(Files.exists(canonical.resolveSibling(canonical.getFileName() + ".tmp")));
    }

    @Test
    void concurrentWrites_leaveOneCompleteReadableCache() throws Exception {
        Path canonical = tempDir.resolve("concurrent-cache.json");
        Path legacy = tempDir.resolve("missing-concurrent-legacy.json");
        HttpDiskCache cache = new HttpDiskCache(canonical, legacy);
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

        HttpDiskCache reloaded = new HttpDiskCache(canonical, legacy);
        for (int index = 0; index < 20; index++) {
            assertEquals("body-" + index, reloaded.get("url-" + index).body);
        }
    }
}
