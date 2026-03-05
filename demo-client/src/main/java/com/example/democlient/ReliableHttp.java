package com.example.democlient;

import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

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
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) throws java.io.IOException {
                return false; // 任何状态码都不当成异常抛出
            }
        });
        this.retryPolicy = retryPolicy;
        this.breaker = breaker;
    }

    /**
     * GET JSON (string) with optional If-None-Match.
     * - 成功：返回 ResponseEntity（200 或 304）
     * - 失败：按策略重试；最终失败抛异常
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
                // 200/304：成功，关闭熔断趋势
                if (code == 200 || code == 304) {
                    breaker.recordSuccess();
                    return resp;
                }
                // 429：过载，记失败 + 不重试（避免越打越爆）
                if (code == 429) {
                    breaker.recordFailure();
                    throw new IllegalStateException("HTTP_429_TOO_MANY_REQUESTS");
                }
                // 5xx：服务端故障，记失败 + 按策略重试
                if (code >= 500 && code <= 599) {
                    breaker.recordFailure();
                    if (attempt < retryPolicy.getMaxAttempts()) {
                        long sleep = retryPolicy.backoffWithJitter(attempt);
                        System.out.println("WARN: server error " + code + ", retry in " + sleep + "ms");
                        Thread.sleep(sleep);
                        continue;
                    }
                    return resp; // 返回给调用方决定怎么处理
                }
                // 其他 4xx（比如 404）：不重试，也不记熔断失败（这是请求问题）
                return resp;
            } catch (ResourceAccessException e) {
                // 超时/连接失败通常走这里
                last = e;
                if (attempt < retryPolicy.getMaxAttempts()) {
                    long sleep = retryPolicy.backoffWithJitter(attempt);
                    System.out.println("WARN: network error, retry in " + sleep + "ms: " + e.getMessage());
                    Thread.sleep(sleep);
                    continue;
                }
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < retryPolicy.getMaxAttempts()) {
                    long sleep = retryPolicy.backoffWithJitter(attempt);
                    System.out.println("WARN: unexpected error, retry in " + sleep + "ms: " + e.getMessage());
                    Thread.sleep(sleep);
                    continue;
                }
                throw e;
            }
        }

        // 理论上到不了
        throw new IllegalStateException("Request failed", last);
    }
}