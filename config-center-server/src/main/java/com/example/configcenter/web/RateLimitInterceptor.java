package com.example.configcenter.web;

import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties props;
    private static final java.util.concurrent.atomic.LongAdder RATE_LIMIT_BLOCKED = new java.util.concurrent.atomic.LongAdder();

    // key: ip + method + uri
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitInterceptor(RateLimitProperties props) {
        this.props = props;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        if (!props.isEnabled()) return true;

        String uri = request.getRequestURI();

        // 只限 /api/**；放过 swagger、h2
        if (!uri.startsWith("/api/")) return true;

        String method = request.getMethod();
        String ip = request.getRemoteAddr();
        String key = ip + "|" + method + "|" + uri;

        TokenBucket bucket = buckets.computeIfAbsent(
                key, k -> new TokenBucket(props.getCapacity(), props.getRefillPerSecond())
        );

        if (!bucket.tryConsume(1)) {
            RATE_LIMIT_BLOCKED.increment();
            throw new BizException(ErrorCode.RATE_LIMIT, "请求过于频繁，请稍后再试");
        }

        return true;
    }
    public static long getBlockedCount() {
        return RATE_LIMIT_BLOCKED.sum();
    }
}