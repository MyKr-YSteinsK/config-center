package com.example.configcenter.dto.response;

import java.time.Instant;

public class ConfigHistoryDto {
    private String app;
    private String env;
    private String key;
    private String value;
    private String description;
    private long version;
    private String action;
    private String operator;
    private String reason;
    private Instant createdAt;

    public ConfigHistoryDto() {}

    public ConfigHistoryDto(String app, String env, String key, String value, String description,
                            long version, String action, String operator, String reason, Instant createdAt) {
        this.app = app;
        this.env = env;
        this.key = key;
        this.value = value;
        this.description = description;
        this.version = version;
        this.action = action;
        this.operator = operator;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public String getApp() { return app; }
    public String getEnv() { return env; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public String getDescription() { return description; }
    public long getVersion() { return version; }
    public String getAction() { return action; }
    public String getOperator() { return operator; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}