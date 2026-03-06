package com.example.configcenter.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * feature 历史返回对象。
 */
public class FeatureHistoryDto {

    private String app;
    private String env;
    private String name;

    private boolean enabled;
    private int rolloutPercentage;
    private List<String> allowlist;

    private long version;
    private String action;
    private String operator;
    private String reason;
    private Instant createdAt;

    public FeatureHistoryDto() {}

    public FeatureHistoryDto(String app, String env, String name, boolean enabled, int rolloutPercentage,
                             List<String> allowlist, long version, String action,
                             String operator, String reason, Instant createdAt) {
        this.app = app;
        this.env = env;
        this.name = name;
        this.enabled = enabled;
        this.rolloutPercentage = rolloutPercentage;
        this.allowlist = allowlist;
        this.version = version;
        this.action = action;
        this.operator = operator;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public String getApp() { return app; }
    public String getEnv() { return env; }
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public int getRolloutPercentage() { return rolloutPercentage; }
    public List<String> getAllowlist() { return allowlist; }
    public long getVersion() { return version; }
    public String getAction() { return action; }
    public String getOperator() { return operator; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
