package com.example.democlient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClientHttpConfiguration {

    @Bean
    @Qualifier("standardHttp")
    HttpFetcher standardHttp(
            @Value("${demo.http.connectTimeoutMs:800}") int connectTimeoutMs,
            @Value("${demo.http.readTimeoutMs:3000}") int readTimeoutMs) {
        return reliableHttp(connectTimeoutMs, readTimeoutMs);
    }

    @Bean
    @Qualifier("watchHttp")
    HttpFetcher watchHttp(
            @Value("${demo.http.connectTimeoutMs:800}") int connectTimeoutMs,
            @Value("${demo.watch.timeoutSeconds:10}") int timeoutSeconds,
            @Value("${demo.watch.readTimeoutMarginMs:2000}") int marginMs) {
        return reliableHttp(connectTimeoutMs, watchReadTimeoutMs(timeoutSeconds, marginMs));
    }

    static int watchReadTimeoutMs(int timeoutSeconds, int marginMs) {
        if (timeoutSeconds < 1 || marginMs < 1) {
            throw new IllegalArgumentException("watch timeout and margin must be positive");
        }
        return Math.addExact(Math.multiplyExact(timeoutSeconds, 1000), marginMs);
    }

    private ReliableHttp reliableHttp(int connectTimeoutMs, int readTimeoutMs) {
        return new ReliableHttp(
                connectTimeoutMs,
                readTimeoutMs,
                new RetryPolicy(3, 200, 2000),
                new CircuitBreaker(2, 5000)
        );
    }
}
