package com.example.configcenter;

import com.example.configcenter.service.FeatureEvaluator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FeatureEvaluatorTest {

    private final FeatureEvaluator evaluator = new FeatureEvaluator();

    @Test
    void enabledFalse_alwaysFalse() {
        assertFalse(evaluator.evaluate(false, List.of("u1"), 100, "u1", "f1"));
    }

    @Test
    void allowlist_hit_true() {
        assertTrue(evaluator.evaluate(true, List.of("u1"), 0, "u1", "f1"));
    }

    @Test
    void rollout0_alwaysFalse_ifNotAllowlist() {
        assertFalse(evaluator.evaluate(true, List.of(), 0, "u2", "f1"));
    }

    @Test
    void rollout100_alwaysTrue_ifNotAllowlist() {
        assertTrue(evaluator.evaluate(true, List.of(), 100, "u2", "f1"));
    }

    @Test
    void sameUser_sameFeature_bucketStable() {
        int b1 = evaluator.stableBucket("u999", "feature-x");
        int b2 = evaluator.stableBucket("u999", "feature-x");
        assertEquals(b1, b2);
    }

    @Test
    void bucketRange_0to99() {
        int b = evaluator.stableBucket("u123", "feature-y");
        assertTrue(b >= 0 && b <= 99);
    }

    @Test
    void differentUsers_haveDiversityInBuckets() {
        // 不断言“必然不同”，只要分布有一定多样性即可
        List<Integer> buckets = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            buckets.add(evaluator.stableBucket("u" + i, "feature-z"));
        }
        long unique = buckets.stream().distinct().count();
        assertTrue(unique > 10, "bucket 分布应具有一定多样性");
    }
}