package com.example.configcenter.web;

import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 一个很轻量的限流拦截器。
 * 它不是生产级网关，但足够把“别让接口被无脑打爆”这个意识带进项目里。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties props;
    private final LongAdder blockedCount = new LongAdder();

    // 访问顺序 map + 固定上限，避免不同来源地址永久累积桶。
    private final LinkedHashMap<String, TokenBucket> buckets =
            new LinkedHashMap<>(16, 0.75f, true);

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
        String key = ip + "|" + method + "|" + routePattern(request);

        TokenBucket bucket = bucketFor(key);

        if (!bucket.tryConsume(1)) {
            blockedCount.increment();
            throw new BizException(ErrorCode.RATE_LIMIT, "请求过于频繁，请稍后再试");
        }

        return true;
    }

    public long getBlockedCount() {
        return blockedCount.sum();
    }

    public synchronized int getBucketCount() {
        return buckets.size();
    }

    private String routePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }

    private synchronized TokenBucket bucketFor(String key) {
        TokenBucket existing = buckets.get(key);
        if (existing != null) {
            return existing;
        }

        if (buckets.size() >= props.getMaxBuckets()) {
            Iterator<Map.Entry<String, TokenBucket>> iterator = buckets.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }

        TokenBucket created = new TokenBucket(props.getCapacity(), props.getRefillPerSecond());
        buckets.put(key, created);
        return created;
    }
}
