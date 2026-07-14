package com.example.democlient;

public interface ConfigCache {

    HttpDiskCache.Entry get(String url);

    void put(String url, String etag, String body);
}
