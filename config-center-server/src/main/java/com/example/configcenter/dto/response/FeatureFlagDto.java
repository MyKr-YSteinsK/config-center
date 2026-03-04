package com.example.configcenter.dto.response;

import java.time.Instant;
import java.util.List;

public class FeatureFlagDto {

    private String app;
    private String env;
    private String name;
    private boolean enabled;
    private int rolloutPercentage;
    private List<String> allowlist;
    private Instant updatedAt;

    public FeatureFlagDto() {}

    public FeatureFlagDto(String app, String env, String name, boolean enabled,
                          int rolloutPercentage, List<String> allowlist, Instant updatedAt) {
        this.app = app;
        this.env = env;
        this.name = name;
        this.enabled = enabled;
        this.rolloutPercentage = rolloutPercentage;
        this.allowlist = allowlist;
        this.updatedAt = updatedAt;
    }

    public String getApp() { return app; }
    public String getEnv() { return env; }
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public int getRolloutPercentage() { return rolloutPercentage; }
    public List<String> getAllowlist() { return allowlist; }
    public Instant getUpdatedAt() { return updatedAt; }
}