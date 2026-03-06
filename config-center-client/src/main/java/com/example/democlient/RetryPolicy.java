package com.example.democlient;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试退避策略。
 * 指数退避 + 抖动是老朋友了，简单但好用，至少不会所有重试都卡着同一拍子撞过去。
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long backoffWithJitter(int attemptIndex) {
        long exp = baseDelayMs * (1L << Math.min(attemptIndex - 1, 10));
        long capped = Math.min(exp, maxDelayMs);

        // jitter 取 [0.5, 1.5)，别让所有请求都一个节奏重试。
        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble();
        return (long) (capped * jitter);
    }
}
