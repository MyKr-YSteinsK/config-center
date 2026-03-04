package com.example.configcenter.dto.response;

/**
 * evaluate 不仅给 true/false，还解释“为什么”
 */
public class FeatureEvalResult {

    private String name;
    private String userId;
    private boolean enabled;

    private int bucket;      // 0..99；如果不走灰度（比如 allowlist / enabled=false），用 -1
    private String decision; // explain

    public FeatureEvalResult() {}

    public FeatureEvalResult(String name, String userId, boolean enabled, int bucket, String decision) {
        this.name = name;
        this.userId = userId;
        this.enabled = enabled;
        this.bucket = bucket;
        this.decision = decision;
    }

    public String getName() { return name; }
    public String getUserId() { return userId; }
    public boolean isEnabled() { return enabled; }
    public int getBucket() { return bucket; }
    public String getDecision() { return decision; }
}