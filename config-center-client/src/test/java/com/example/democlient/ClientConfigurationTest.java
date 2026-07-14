package com.example.democlient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
