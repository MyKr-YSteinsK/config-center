package com.example.democlient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class HttpDiskCache implements ConfigCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final Path legacyFile;
    private final CacheFileMover fileMover;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public HttpDiskCache() {
        this(
                Paths.get(System.getProperty("user.home"), ".config-center-client-cache.json"),
                Paths.get(System.getProperty("user.home"), ".config-center-demo-client-cache.json")
        );
    }

    HttpDiskCache(Path file, Path legacyFile) {
        this(file, legacyFile, Files::move);
    }

    HttpDiskCache(Path file, Path legacyFile, CacheFileMover fileMover) {
        this.file = file;
        this.legacyFile = legacyFile;
        this.fileMover = fileMover;
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
    public synchronized void put(String url, String etag, String body) {
        cache.put(url, new Entry(etag, body));
        save();
    }

    private void load() {
        if (Files.exists(file)) {
            loadFrom(file);
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
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(cache);
            Files.write(tempFile, bytes);
            replace(tempFile);
        } catch (Exception e) {
            System.out.println("WARN: failed to save cache file: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                System.out.println("WARN: failed to clean cache temp file: " + e.getMessage());
            }
        }
    }

    private void replace(Path tempFile) throws IOException {
        try {
            fileMover.move(tempFile, file,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            fileMover.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    interface CacheFileMover {
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }
}
