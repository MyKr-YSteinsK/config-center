package com.example.democlient;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.channels.ClosedByInterruptException;

public class ReliableHttp implements HttpFetcher {

    private final RestOperations restOperations;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker breaker;
    private final Sleeper sleeper;

    public ReliableHttp(int connectTimeoutMs, int readTimeoutMs,
                        RetryPolicy retryPolicy, CircuitBreaker breaker) {
        this(restTemplate(connectTimeoutMs, readTimeoutMs), retryPolicy, breaker, Thread::sleep);
    }

    ReliableHttp(RestOperations restOperations, RetryPolicy retryPolicy,
                 CircuitBreaker breaker, Sleeper sleeper) {
        this.restOperations = restOperations;
        this.retryPolicy = retryPolicy;
        this.breaker = breaker;
        this.sleeper = sleeper;
    }

    @Override
    public ResponseEntity<String> getWithRetry(String url, String ifNoneMatch) throws InterruptedException {
        checkInterrupted();
        URI uri = URI.create(url);
        HttpHeaders headers = new HttpHeaders();
        if (ifNoneMatch != null) {
            headers.setIfNoneMatch(ifNoneMatch);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        if (!breaker.allowRequest()) {
            throw HttpRequestFailedException.circuitOpen(breaker.snapshot());
        }

        try {
            return exchangeWithRetry(uri, entity);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        } finally {
            breaker.releaseProbe();
        }
    }

    private ResponseEntity<String> exchangeWithRetry(URI uri, HttpEntity<Void> entity) throws InterruptedException {
        for (int attempt = 1; attempt <= retryPolicy.getMaxAttempts(); attempt++) {
            checkInterrupted();
            try {
                ResponseEntity<String> response = restOperations.exchange(uri, HttpMethod.GET, entity, String.class);
                checkInterrupted();
                int status = response.getStatusCode().value();

                if (status == 200 || status == 304) {
                    breaker.recordSuccess();
                    return response;
                }

                if (status >= 500 && status <= 599) {
                    breaker.recordFailure();
                    if (attempt < retryPolicy.getMaxAttempts()) {
                        backoff(attempt);
                        continue;
                    }
                    throw new HttpRequestFailedException(
                            "HTTP_" + status + " after " + attempt + " attempts", status, true);
                }

                // 4xx（包括 429）证明服务可达，不污染仅描述可用性的断路器。
                breaker.recordSuccess();
                if (status == 429) {
                    throw new HttpRequestFailedException("HTTP_429_TOO_MANY_REQUESTS", status, false);
                }

                throw new HttpRequestFailedException("HTTP_" + status, status, false);
            } catch (HttpRequestFailedException e) {
                throw e;
            } catch (ResourceAccessException e) {
                checkInterrupted();
                Throwable cause = e.getCause();
                if (cause instanceof ClosedByInterruptException
                        || (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException))) {
                    InterruptedException cancelled = new InterruptedException("HTTP request interrupted");
                    cancelled.initCause(e);
                    throw cancelled;
                }
                breaker.recordFailure();
                if (attempt < retryPolicy.getMaxAttempts()) {
                    backoff(attempt);
                    continue;
                }
                throw new HttpRequestFailedException(
                        "Network request failed after " + attempt + " attempts ("
                                + e.getMostSpecificCause().getClass().getSimpleName() + ")", true, e);
            } catch (RuntimeException e) {
                checkInterrupted();
                breaker.recordFailure();
                throw e;
            }
        }

        throw new HttpRequestFailedException("Request failed", true, null);
    }

    static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Client interrupted");
        }
    }

    private void backoff(int attempt) throws InterruptedException {
        checkInterrupted();
        sleeper.sleep(retryPolicy.backoffWithJitter(attempt));
    }

    private static RestTemplate restTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return restTemplate;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
