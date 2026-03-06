package com.example.configcenter.dto.response;

import java.time.Instant;

/**
 * 配置项对外展示对象。
 * 保留 version 和 updatedAt，客户端做缓存、watch 或展示时都能直接拿来用。
 */
public class ConfigItemDto {

    private String app;
    private String env;
    private String key;
    private String value;
    private String description;
    private long version;
    private Instant updatedAt;

    public ConfigItemDto() {}

    public ConfigItemDto(String app, String env, String key, String value,
                         String description, long version, Instant updatedAt) {
        this.app = app;
        this.env = env;
        this.key = key;
        this.value = value;
        this.description = description;
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public String getApp() { return app; }
    public String getEnv() { return env; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public String getDescription() { return description; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
