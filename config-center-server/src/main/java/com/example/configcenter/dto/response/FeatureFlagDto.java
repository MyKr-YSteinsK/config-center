package com.example.configcenter.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * feature 当前状态的返回对象。
 */
public class FeatureFlagDto {

    private String app;
    private String env;
    private String name;
    private boolean enabled;
    private int rolloutPercentage;
    private List<String> allowlist;
    private Instant updatedAt;
    private long version;

    public FeatureFlagDto(String app, String env, String name, boolean enabled, int rolloutPercentage,
                          List<String> allowlist, Instant updatedAt, long version) {
        this.app = app;
        this.env = env;
        this.name = name;
        this.enabled = enabled;
        this.rolloutPercentage = rolloutPercentage;
        this.allowlist = allowlist;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getApp() { return app; }
    public String getEnv() { return env; }
    public String getName() { return name; }
    public long getVersion() { return version; }
    public boolean isEnabled() { return enabled; }
    public int getRolloutPercentage() { return rolloutPercentage; }
    public List<String> getAllowlist() { return allowlist; }
    public Instant getUpdatedAt() { return updatedAt; }
}
