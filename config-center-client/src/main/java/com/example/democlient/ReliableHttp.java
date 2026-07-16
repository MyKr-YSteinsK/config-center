package com.example.democlient;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

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
        if (!breaker.allowRequest()) {
            throw new HttpRequestFailedException("CIRCUIT_OPEN: " + breaker.snapshot(), false, null);
        }

        HttpHeaders headers = new HttpHeaders();
        if (ifNoneMatch != null) {
            headers.setIfNoneMatch(ifNoneMatch);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        for (int attempt = 1; attempt <= retryPolicy.getMaxAttempts(); attempt++) {
            try {
                ResponseEntity<String> response = restOperations.exchange(
                        url, HttpMethod.GET, entity, String.class);
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
                breaker.recordFailure();
                if (attempt < retryPolicy.getMaxAttempts()) {
                    backoff(attempt);
                    continue;
                }
                throw new HttpRequestFailedException(
                        "Network request failed after " + attempt + " attempts", true, e);
            } catch (RuntimeException e) {
                // 未预期异常也必须释放 HALF_OPEN 的单探测槽，避免断路器永久卡住。
                breaker.recordFailure();
                throw e;
            }
        }

        throw new HttpRequestFailedException("Request failed", true, null);
    }

    private void backoff(int attempt) throws InterruptedException {
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
