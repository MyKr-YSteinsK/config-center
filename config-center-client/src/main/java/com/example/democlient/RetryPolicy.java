package com.example.democlient;

import java.util.concurrent.ThreadLocalRandom;

public class RetryPolicy {

    private final int maxAttempts;         // 总尝试次数：1=不重试
    private final long baseDelayMs;        // 初始退避
    private final long maxDelayMs;         // 最大退避

    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long backoffWithJitter(int attemptIndex) {
        // attemptIndex: 1,2,3...（第1次重试开始算）
        long exp = baseDelayMs * (1L << Math.min(attemptIndex - 1, 10)); // 防止移位过大
        long capped = Math.min(exp, maxDelayMs);

        // jitter: [0.5, 1.5) 倍
        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble();
        return (long) (capped * jitter);
    }
}