package com.example.democlient;

/**
 * 一个很轻量的断路器。
 * 目的不是做成 Hystrix 替代品，而是让客户端遇到连续失败时别一头猛撞服务端。
 */
public class CircuitBreaker {

    enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;

    private State state = State.CLOSED;
    private int failures = 0;
    private long openUntilEpochMs = 0;
    private boolean halfOpenInFlight = false;

    public CircuitBreaker(int failureThreshold, long openMillis) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    public synchronized boolean allowRequest() {
        long now = System.currentTimeMillis();

        if (state == State.CLOSED) return true;

        if (state == State.OPEN) {
            if (now >= openUntilEpochMs) {
                state = State.HALF_OPEN;
                halfOpenInFlight = false;
            } else {
                return false;
            }
        }

        // HALF_OPEN 只放一个探测请求过去，先看看服务是不是缓过来了。
        if (state == State.HALF_OPEN) {
            if (halfOpenInFlight) return false;
            halfOpenInFlight = true;
            return true;
        }

        return false;
    }

    public synchronized void recordSuccess() {
        failures = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            halfOpenInFlight = false;
        }
    }

    public synchronized void recordFailure() {
        failures++;

        if (state == State.HALF_OPEN) {
            open();
            return;
        }

        if (state == State.CLOSED && failures >= failureThreshold) {
            open();
        }
    }

    private void open() {
        state = State.OPEN;
        openUntilEpochMs = System.currentTimeMillis() + openMillis;
        halfOpenInFlight = false;
    }

    public synchronized String snapshot() {
        return "state=" + state + ", failures=" + failures + ", openUntilMs=" + openUntilEpochMs;
    }
}
