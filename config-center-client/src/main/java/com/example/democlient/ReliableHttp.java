package com.example.democlient;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * 带超时、重试和断路器的 HTTP 拉取器。
 * 这个类基本就是客户端可靠性演示的核心。
 */
public class ReliableHttp {

    private final RestTemplate restTemplate;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker breaker;

    public ReliableHttp(int connectTimeoutMs, int readTimeoutMs, RetryPolicy retryPolicy, CircuitBreaker breaker) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectTimeoutMs);
        f.setReadTimeout(readTimeoutMs);

        this.restTemplate = new RestTemplate(f);

        this.restTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        this.retryPolicy = retryPolicy;
        this.breaker = breaker;
    }

    /**
     * 读取字符串响应，支持可选的 If-None-Match。
     */
    public ResponseEntity<String> getWithRetry(String url, String ifNoneMatch) throws InterruptedException {
        if (!breaker.allowRequest()) {
            throw new IllegalStateException("CIRCUIT_OPEN: " + breaker.snapshot());
        }

        HttpHeaders headers = new HttpHeaders();
        if (ifNoneMatch != null) {
            headers.set("If-None-Match", ifNoneMatch);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        Exception last = null;

        for (int attempt = 1; attempt <= retryPolicy.getMaxAttempts(); attempt++) {
            try {
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                int code = resp.getStatusCode().value();

                // 200 / 304 都算成功，说明服务端是正常响应的。
                if (code == 200 || code == 304) {
                    breaker.recordSuccess();
                    return resp;
                }

                // 429 时别硬顶着重试，不然越限流越打爆。
                if (code == 429) {
                    breaker.recordFailure();
                    throw new IllegalStateException("HTTP_429_TOO_MANY_REQUESTS");
                }

                // 5xx 认为是服务端暂时不稳，可以按策略试几次。
                if (code >= 500 && code <= 599) {
                    breaker.recordFailure();
                    if (attempt < retryPolicy.getMaxAttempts()) {
                        long sleep = retryPolicy.backoffWithJitter(attempt);
                        System.out.println("WARN: server error " + code + ", retry in " + sleep + "ms");
                        Thread.sleep(sleep);
                        continue;
                    }
                    return resp;
                }

                // 其他 4xx 更像请求本身有问题，不适合盲目重试。
                return resp;
            } catch (ResourceAccessException e) {
                last = e;
                breaker.recordFailure();
                if (attempt < retryPolicy.getMaxAttempts()) {
                    long sleep = retryPolicy.backoffWithJitter(attempt);
                    System.out.println("WARN: network error, retry in " + sleep + "ms: " + e.getMessage());
                    Thread.sleep(sleep);
                    continue;
                }
                throw e;
            } catch (Exception e) {
                last = e;
                if (!(e instanceof IllegalStateException state && state.getMessage() != null && state.getMessage().startsWith("HTTP_429"))) {
                    breaker.recordFailure();
                }
                if (attempt < retryPolicy.getMaxAttempts()) {
                    long sleep = retryPolicy.backoffWithJitter(attempt);
                    System.out.println("WARN: unexpected error, retry in " + sleep + "ms: " + e.getMessage());
                    Thread.sleep(sleep);
                    continue;
                }
                throw e;
            }
        }

        throw new IllegalStateException("Request failed", last);
    }
}
