package com.example.configcenter.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ComposeConfigurationTest {

    @Test
    void serverUsesInternalWildcardBindingAndLoopbackHostPublication() throws IOException {
        Map<String, Object> compose = loadCompose();
        Map<String, Object> services = map(compose.get("services"));
        Map<String, Object> server = map(services.get("config-center-server"));
        Map<String, Object> environment = map(server.get("environment"));

        assertEquals("0.0.0.0", environment.get("SERVER_ADDRESS"));
        assertEquals(
                "${SERVER_BIND_ADDRESS:-127.0.0.1}:${SERVER_PORT:-8080}:8080",
                list(server.get("ports")).get(0));
        assertFalse(map(services.get("mysql")).containsKey("ports"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadCompose() throws IOException {
        try (InputStream input = Files.newInputStream(Path.of("..", "compose.yml"))) {
            return new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<String> list(Object value) {
        return (List<String>) value;
    }
}
