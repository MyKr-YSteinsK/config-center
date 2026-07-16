package com.example.configcenter.web;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 限流配置项。
 */
@ConfigurationProperties(prefix = "rate-limit")
@Validated
public class RateLimitProperties {

    private boolean enabled = true;

    @Positive
    private long capacity = 5;

    @PositiveOrZero
    private double refillPerSecond = 5.0;

    @Positive
    private int maxBuckets = 256;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }

    public double getRefillPerSecond() { return refillPerSecond; }
    public void setRefillPerSecond(double refillPerSecond) { this.refillPerSecond = refillPerSecond; }

    public int getMaxBuckets() { return maxBuckets; }
    public void setMaxBuckets(int maxBuckets) { this.maxBuckets = maxBuckets; }
}
