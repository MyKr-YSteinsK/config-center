package com.example.democlient;

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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                eq("http://test"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void serverErrors_retryUntilSuccess() throws Exception {
        when(exchange())
                .thenReturn(ResponseEntity.status(500).body("error"))
                .thenReturn(ResponseEntity.status(503).body("error"))
                .thenReturn(ResponseEntity.ok("ok"));

        ResponseEntity<String> response = http.getWithRetry("http://test", null);

        assertEquals("ok", response.getBody());
        assertEquals(2, sleeps.size());
        verify(restOperations, times(3)).exchange(
                eq("http://test"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
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
                eq("http://test"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
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
                eq("http://test"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String> exchange() {
        return restOperations.exchange(
                eq("http://test"), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }
}
