package com.example.democlient;

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

        // HALF_OPEN：只允许 1 个探测请求
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
        if (state == State.CLOSED) {
            // keep closed
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