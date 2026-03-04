package com.example.configcenter.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 评估逻辑抽成纯 Java：
 * - 便于单测（不依赖 Spring）
 */
public class FeatureEvaluator {

    /**
     * stableHash：用 SHA-256 保证稳定、跨语言一致
     * bucket：hash % 100 落在 0..99
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
            return raw & 0x7fffffff; // 保证非负
        } catch (Exception e) {
            throw new IllegalStateException("hash 计算失败", e);
        }
    }

    /**
     * 规则顺序（必须）：
     * 1) enabled=false -> false
     * 2) allowlist 命中 -> true
     * 3) 灰度：bucket < rolloutPercentage -> true else false
     */
    public boolean evaluate(boolean enabled, List<String> allowlist, int rolloutPercentage,
                            String userId, String featureName) {
        if (!enabled) return false;
        if (allowlist != null && allowlist.contains(userId)) return true;
        int bucket = stableBucket(userId, featureName);
        return bucket < rolloutPercentage;
    }
}