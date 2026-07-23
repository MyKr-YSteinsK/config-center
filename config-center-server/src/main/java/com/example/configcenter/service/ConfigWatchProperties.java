package com.example.configcenter.service;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Watch 长轮询等待队列的进程内容量限制。
 */
@Component
@ConfigurationProperties(prefix = "config-watch")
@Validated
public class ConfigWatchProperties {

    @Positive
    private int maxPendingWaiters = 256;

    @Positive
    private int maxPendingPerNamespace = 64;

    public int getMaxPendingWaiters() {
        return maxPendingWaiters;
    }

    public void setMaxPendingWaiters(int maxPendingWaiters) {
        this.maxPendingWaiters = maxPendingWaiters;
    }

    public int getMaxPendingPerNamespace() {
        return maxPendingPerNamespace;
    }

    public void setMaxPendingPerNamespace(int maxPendingPerNamespace) {
        this.maxPendingPerNamespace = maxPendingPerNamespace;
    }
}
