package com.example.democlient;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;

import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReliableHttpTest {

    @Mock
    private RestOperations restOperations;

    private List<Long> sleeps;
    private ReliableHttp http;

    @BeforeEach
    void setUp() {
        sleeps = new ArrayList<>();
        http = new ReliableHttp(
                restOperations,
                new RetryPolicy(3, 1, 1),
                new CircuitBreaker(10, 1000),
                sleeps::add
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 429})
    void clientErrors_failWithoutRetry(int status) {
        when(exchange()).thenReturn(ResponseEntity.status(status).body("error"));

        HttpRequestFailedException error = assertThrows(
                HttpRequestFailedException.class,
                () -> http.getWithRetry("http://test", null)
        );

        assertEquals(status, error.getStatusCode());
        assertFalse(error.isCacheFallbackAllowed());
        assertTrue(sleeps.isEmpty());
        verify(restOperations).exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 429})
    void repeatedCallerErrors_neverOpenAvailabilityBreaker(int status) {
        when(exchange()).thenReturn(ResponseEntity.status(status).body("error"));
        CircuitBreaker breaker = new CircuitBreaker(2, 1_000);
        ReliableHttp repeatedHttp = new ReliableHttp(
                restOperations, new RetryPolicy(1, 1, 1), breaker, sleeps::add);

        for (int call = 0; call < 3; call++) {
            HttpRequestFailedException error = assertThrows(
                    HttpRequestFailedException.class,
                    () -> repeatedHttp.getWithRetry("http://test", null));
            assertFalse(error.isCacheFallbackAllowed());
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        verify(restOperations, times(3)).exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void serverErrors_retryUntilSuccess() throws Exception {
        when(exchange())
                .thenReturn(ResponseEntity.status(500).body("error"))
                .thenReturn(ResponseEntity.status(503).body("error"))
                .thenReturn(ResponseEntity.ok("ok"));
        CircuitBreaker breaker = new CircuitBreaker(2, 1_000);
        ReliableHttp recoveringHttp = new ReliableHttp(
                restOperations, new RetryPolicy(3, 1, 1), breaker, sleeps::add);

        ResponseEntity<String> response = recoveringHttp.getWithRetry("http://test", null);

        assertEquals("ok", response.getBody());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(2, sleeps.size());
        verify(restOperations, times(3)).exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void exhaustedServerErrors_allowCacheFallback() {
        when(exchange()).thenReturn(ResponseEntity.status(500).body("error"));

        HttpRequestFailedException error = assertThrows(
                HttpRequestFailedException.class,
                () -> http.getWithRetry("http://test", null)
        );

        assertTrue(error.isCacheFallbackAllowed());
        assertEquals(2, sleeps.size());
        verify(restOperations, times(3)).exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void openCircuit_rejectsWithoutCacheFallback() {
        when(exchange()).thenReturn(ResponseEntity.status(503).body("error"));
        CircuitBreaker breaker = new CircuitBreaker(1, 1_000);
        ReliableHttp guardedHttp = new ReliableHttp(
                restOperations, new RetryPolicy(1, 1, 1), breaker, sleeps::add);

        HttpRequestFailedException transientFailure = assertThrows(
                HttpRequestFailedException.class,
                () -> guardedHttp.getWithRetry("http://test", null));
        HttpRequestFailedException circuitOpen = assertThrows(
                HttpRequestFailedException.class,
                () -> guardedHttp.getWithRetry("http://test", null));

        assertTrue(transientFailure.isCacheFallbackAllowed());
        assertFalse(circuitOpen.isCacheFallbackAllowed());
        assertTrue(circuitOpen.isRecoverable());
        assertTrue(circuitOpen.getMessage().startsWith("CIRCUIT_OPEN:"));
        verify(restOperations).exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void networkFailureRetriesDeterministically() throws Exception {
        when(exchange())
                .thenThrow(new ResourceAccessException("offline"))
                .thenReturn(ResponseEntity.ok("ok"));

        ResponseEntity<String> response = http.getWithRetry("http://test", null);

        assertEquals("ok", response.getBody());
        assertEquals(1, sleeps.size());
        verify(restOperations, times(2)).exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void unexpectedHalfOpenFailure_reopensInsteadOfOccupyingProbeForever() {
        AtomicLong clock = new AtomicLong(1_000);
        CircuitBreaker breaker = new CircuitBreaker(1, 100, clock::get);
        breaker.recordFailure();
        clock.addAndGet(100);
        ReliableHttp guardedHttp = new ReliableHttp(
                restOperations, new RetryPolicy(1, 1, 1), breaker, sleeps::add);
        when(exchange()).thenThrow(new IllegalStateException("unexpected"));

        assertThrows(IllegalStateException.class,
                () -> guardedHttp.getWithRetry("http://test", null));

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        clock.addAndGet(100);
        assertTrue(breaker.allowRequest());
    }

    @Test
    void interruptedBackoffRestoresTheSignalAndStopsInternalRetries() {
        when(exchange()).thenReturn(ResponseEntity.status(503).build());
        ReliableHttp cancelledHttp = new ReliableHttp(
                restOperations, new RetryPolicy(3, 1, 1), new CircuitBreaker(2, 1_000),
                millis -> { throw new InterruptedException("cancel backoff"); });

        try {
            assertThrows(InterruptedException.class, () -> cancelledHttp.getWithRetry("http://test", null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        verify(restOperations).exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancelledInFlightProbeDoesNotApplyAResponseOrKeepItsSlot(boolean returnsResponse) {
        AtomicLong clock = new AtomicLong();
        CircuitBreaker breaker = new CircuitBreaker(1, 100, clock::get);
        breaker.recordFailure();
        clock.set(100);
        var guardedHttp = new ReliableHttp(restOperations, new RetryPolicy(3, 1, 1), breaker, sleeps::add);

        when(exchange()).thenAnswer(invocation -> {
            if (returnsResponse) {
                Thread.currentThread().interrupt();
                return ResponseEntity.ok("ignored");
            }
            throw new ResourceAccessException("cancelled", new InterruptedIOException("cancelled"));
        });

        try {
            assertThrows(InterruptedException.class, () -> guardedHttp.getWithRetry("http://test", null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertTrue(sleeps.isEmpty());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.allowRequest());
        verify(restOperations).exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void cancelDuringRealSocketReadWaitsForIoToReturnAndPreventsAnotherRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requestArrived = new CountDownLatch(1);
        var releaseServer = new CountDownLatch(1);
        var requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestArrived.countDown();
            try {
                releaseServer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var result = new AtomicReference<Throwable>();
        var interruptedOnExit = new AtomicReference<Boolean>();
        ReliableHttp transport = new ReliableHttp(800, 800, new RetryPolicy(3, 1, 1),
                new CircuitBreaker(2, 1000));
        Thread client = new Thread(() -> {
            try {
                transport.getWithRetry("http://127.0.0.1:" + server.getAddress().getPort(), null);
            } catch (Exception failure) {
                result.set(failure);
            } finally {
                interruptedOnExit.set(Thread.currentThread().isInterrupted());
            }
        });
        client.setDaemon(true);
        try {
            client.start();
            assertTrue(requestArrived.await(3, TimeUnit.SECONDS));
            client.interrupt();
            client.join(5_000);

            assertFalse(client.isAlive());
            assertInstanceOf(InterruptedException.class, result.get());
            assertEquals(Boolean.TRUE, interruptedOnExit.get());
            assertEquals(1, requests.get());
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    @Test
    void socketReadTimeoutIsARecoverableNetworkError() {
        when(exchange()).thenThrow(new ResourceAccessException("read timeout", new SocketTimeoutException("timeout")));

        var failure = assertThrows(HttpRequestFailedException.class, () -> http.getWithRetry("http://test", null));

        assertTrue(failure.isRecoverable());
        assertTrue(failure.isCacheFallbackAllowed());
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(2, sleeps.size());
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String> exchange() {
        return restOperations.exchange(
                eq(URI.create("http://test")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }
}
