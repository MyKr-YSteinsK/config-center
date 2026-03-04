package com.example.configcenter.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "config_item_history",
        indexes = {
                @Index(name = "idx_cfg_hist_app_env_key", columnList = "app, env, config_key"),
                @Index(name = "idx_cfg_hist_app_env_key_ver", columnList = "app, env, config_key, version")
        }
)
public class ConfigItemHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String app;

    @Column(nullable = false, length = 50)
    private String env;

    @Column(name = "config_key", nullable = false, length = 200)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 2000)
    private String configValue;

    @Column(length = 500)
    private String description;

    /**
     * 记录当时的业务版本号（与 ConfigItem.version 对齐）
     */
    @Column(nullable = false)
    private long version;

    /**
     * UPSERT / ROLLBACK
     */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(length = 100)
    private String operator;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }

    public String getApp() { return app; }
    public void setApp(String app) { this.app = app; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }

    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}