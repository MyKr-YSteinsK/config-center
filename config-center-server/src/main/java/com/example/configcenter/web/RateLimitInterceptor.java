package com.example.configcenter.web;

import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 一个很轻量的限流拦截器。
 * 它不是生产级网关，但足够把“别让接口被无脑打爆”这个意识带进项目里。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties props;
    private static final LongAdder RATE_LIMIT_BLOCKED = new LongAdder();

    // key = ip + method + uri，粒度不算细腻，但 demo 阶段已经够说明思路了。
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitInterceptor(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!props.isEnabled()) return true;

        String uri = request.getRequestURI();

        // 只限 /api/**，Swagger 和 H2 控制台先放行，不然调试会很烦。
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
