package com.example.configcenter.metrics;

import com.example.configcenter.web.RateLimitInterceptor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 自定义指标注册。
 * 先把限流拦截次数暴露出来，后面无论本地看 actuator 还是接 Prometheus 都有抓手。
 */
@Component
public class CustomMetrics {

    public CustomMetrics(MeterRegistry registry) {
        Gauge.builder("config_center_rate_limit_blocked_total", RateLimitInterceptor::getBlockedCount)
                .description("Total number of requests blocked by rate limit")
                .register(registry);
    }
}
