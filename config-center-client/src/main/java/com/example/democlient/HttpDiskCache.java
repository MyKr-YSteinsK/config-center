package com.example.democlient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HttpDiskCache implements ConfigCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Path legacyFile;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public HttpDiskCache() {
        this(
                Paths.get(System.getProperty("user.home"), ".config-center-client-cache.json"),
                Paths.get(System.getProperty("user.home"), ".config-center-demo-client-cache.json")
        );
    }

    HttpDiskCache(Path file, Path legacyFile) {
        this.file = file;
        this.legacyFile = legacyFile;
        load();
    }

    public static class Entry {
        public String etag;
        public String body;

        public Entry() {}

        public Entry(String etag, String body) {
            this.etag = etag;
            this.body = body;
        }
    }

    @Override
    public Entry get(String url) {
        return cache.get(url);
    }

    @Override
    public void put(String url, String etag, String body) {
        cache.put(url, new Entry(etag, body));
        save();
    }

    private void load() {
        if (loadFrom(file)) {
            return;
        }
        if (loadFrom(legacyFile)) {
            save();
        }
    }

    private boolean loadFrom(Path source) {
        try {
            if (!Files.exists(source) || Files.size(source) == 0) {
                return false;
            }
            Map<String, Entry> loaded = MAPPER.readValue(
                    Files.readAllBytes(source), new TypeReference<Map<String, Entry>>() {});
            cache.clear();
            cache.putAll(loaded);
            return true;
        } catch (Exception e) {
            System.out.println("WARN: failed to load cache file " + source + ": " + e.getMessage());
            return false;
        }
    }

    private void save() {
        try {
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(cache);
            Files.write(file, bytes);
        } catch (Exception e) {
            System.out.println("WARN: failed to save cache file: " + e.getMessage());
        }
    }
}
