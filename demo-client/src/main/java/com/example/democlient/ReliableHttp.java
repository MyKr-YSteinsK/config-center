package com.example.democlient;

import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

public class ReliableHttp {

    private final RestTemplate restTemplate;
    private final RetryPolicy retryPolicy;

    public ReliableHttp(int connectTimeoutMs, int readTimeoutMs, RetryPolicy retryPolicy) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectTimeoutMs);
        f.setReadTimeout(readTimeoutMs);

        this.restTemplate = new RestTemplate(f);
        this.retryPolicy = retryPolicy;
    }

    /**
     * GET JSON (string) with optional If-None-Match.
     * - 成功：返回 ResponseEntity（200 或 304）
     * - 失败：按策略重试；最终失败抛异常
     */
    public ResponseEntity<String> getWithRetry(String url, String ifNoneMatch) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        if (ifNoneMatch != null) {
            headers.set("If-None-Match", ifNoneMatch);
        }
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        Exception last = null;

        for (int attempt = 1; attempt <= retryPolicy.getMaxAttempts(); attempt++) {
            try {
                ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

                // 对 5xx 做重试（服务端临时故障），对 4xx 不重试（请求参数错了）
                int code = resp.getStatusCode().value();
                if (code >= 500 && code <= 599 && attempt < retryPolicy.getMaxAttempts()) {
                    long sleep = retryPolicy.backoffWithJitter(attempt);
                    System.out.println("WARN: server error " + code + ", retry in " + sleep + "ms");
                    Thread.sleep(sleep);
                    continue;
                }

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