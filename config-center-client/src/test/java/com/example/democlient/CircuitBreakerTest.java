package com.example.democlient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private AtomicLong clock;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new AtomicLong(1_000);
        breaker = new CircuitBreaker(2, 100, clock::get);
    }

    @Test
    void failuresMoveClosedToOpen() {
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());

        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void openWindowExpiryAllowsOneHalfOpenProbe() {
        openBreaker();
        clock.addAndGet(100);

        assertTrue(breaker.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    @Test
    void halfOpenSuccessClosesBreaker() {
        enterHalfOpen();

        breaker.recordSuccess();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void halfOpenFailureReopensBreaker() {
        enterHalfOpen();

        breaker.recordFailure();

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    private void openBreaker() {
        breaker.recordFailure();
        breaker.recordFailure();
    }

    private void enterHalfOpen() {
        openBreaker();
        clock.addAndGet(100);
        assertTrue(breaker.allowRequest());
    }
}
