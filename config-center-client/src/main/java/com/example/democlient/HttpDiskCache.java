package com.example.democlient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个很朴素的磁盘缓存。
 * CLI 程序每次跑完都会退出，所以只做内存缓存意义不大，干脆直接落盘。
 */
public class HttpDiskCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 放到用户目录下，项目删了重新拉也不影响缓存文件继续复用。
    private static final Path FILE = Paths.get(System.getProperty("user.home"), ".config-center-demo-client-cache.json");

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    static {
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

    public static Entry get(String url) {
        return CACHE.get(url);
    }

    public static void put(String url, String etag, String body) {
        CACHE.put(url, new Entry(etag, body));
        save();
    }

    private static void load() {
        try {
            if (!Files.exists(FILE)) {
                return;
            }
            byte[] bytes = Files.readAllBytes(FILE);
            if (bytes.length == 0) {
                return;
            }
            Map<String, Entry> m = MAPPER.readValue(bytes, new TypeReference<Map<String, Entry>>() {});
            CACHE.clear();
            CACHE.putAll(m);
        } catch (Exception e) {
            // 缓存坏了也别拖垮主流程，最多就是这次重新拉一遍。
            System.out.println("WARN: failed to load cache file: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(CACHE);
            Files.write(FILE, bytes);
        } catch (Exception e) {
            System.out.println("WARN: failed to save cache file: " + e.getMessage());
        }
    }
}
