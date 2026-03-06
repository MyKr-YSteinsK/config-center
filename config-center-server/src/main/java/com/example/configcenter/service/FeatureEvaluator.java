package com.example.configcenter.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 纯 Java 的 feature 评估器。
 * 单独拆出来后，测试不用拉 Spring 容器，跑起来更轻，也更容易把规则讲清楚。
 */
public class FeatureEvaluator {

    /**
     * 稳定分桶：同一个 userId + featureName，多次计算出来的 bucket 要一致。
     */
    public int stableBucket(String userId, String featureName) {
        int h = stableHash(userId + ":" + featureName);
        return Math.floorMod(h, 100);
    }

    public int stableHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            int raw = ByteBuffer.wrap(digest, 0, 4).getInt();
            return raw & 0x7fffffff;
        } catch (Exception e) {
            throw new IllegalStateException("hash 计算失败", e);
        }
    }

    /**
     * 规则顺序不能乱：
     * 1. 总开关关了，直接 false
     * 2. 白名单命中，直接 true
     * 3. 其他用户走灰度分桶
     */
    public boolean evaluate(boolean enabled, List<String> allowlist, int rolloutPercentage,
                            String userId, String featureName) {
        if (!enabled) return false;
        if (allowlist != null && allowlist.contains(userId)) return true;
        int bucket = stableBucket(userId, featureName);
        return bucket < rolloutPercentage;
    }
}
