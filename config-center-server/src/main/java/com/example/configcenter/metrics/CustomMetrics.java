package com.example.configcenter.metrics;

import com.example.configcenter.web.RateLimitInterceptor;
import com.example.configcenter.service.ConfigWatchNotifier;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 自定义指标注册。
 * 先把限流拦截次数暴露出来，后面无论本地看 actuator 还是接 Prometheus 都有抓手。
 */
@Component
public class CustomMetrics {

    public CustomMetrics(
            MeterRegistry registry,
            RateLimitInterceptor rateLimitInterceptor,
            ConfigWatchNotifier configWatchNotifier) {
        FunctionCounter.builder("config_center_rate_limit_blocked",
                        rateLimitInterceptor, RateLimitInterceptor::getBlockedCount)
                .description("Total number of requests blocked by rate limit")
                .register(registry);
        Gauge.builder("config_center_watch_pending",
                        configWatchNotifier, ConfigWatchNotifier::pendingWaiterCount)
                .description("Current number of pending configuration watch waiters")
                .register(registry);
        Gauge.builder("config_center_watch_namespaces",
                        configWatchNotifier, ConfigWatchNotifier::pendingNamespaceCount)
                .description("Current number of namespaces with pending configuration watch waiters")
                .register(registry);
        FunctionCounter.builder("config_center_watch_rejected",
                        configWatchNotifier, ConfigWatchNotifier::rejectedCount)
                .description("Total number of configuration watch requests rejected for capacity")
                .register(registry);
    }
}
