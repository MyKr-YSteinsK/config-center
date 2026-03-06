package com.example.configcenter.dto.response;

/**
 * feature evaluate 的解释型结果。
 * 不只告诉调用方 true/false，还顺手说清楚这次是怎么判断出来的。
 */
public class FeatureEvalResult {

    private String name;
    private String userId;
    private boolean enabled;

    // 0..99；如果压根没走灰度，比如总开关关闭或命中白名单，这里就记 -1。
    private int bucket;
    private String decision;

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
