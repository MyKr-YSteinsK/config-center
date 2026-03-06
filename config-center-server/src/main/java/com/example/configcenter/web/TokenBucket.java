package com.example.configcenter.web;

/**
 * 最基础的令牌桶实现。
 * 逻辑不复杂，但够用：有令牌就放行，没令牌就拦住，时间过去后再慢慢补回来。
 */
public class TokenBucket {

    private final long capacity;
    private final double refillPerSecond;

    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(long capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryConsume(long n) {
        refill();
        if (tokens >= n) {
            tokens -= n;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos <= 0) return;

        double add = (elapsedNanos / 1_000_000_000.0) * refillPerSecond;
        if (add > 0) {
            tokens = Math.min(capacity, tokens + add);
            lastRefillNanos = now;
        }
    }
}
