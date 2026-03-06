package com.example.configcenter.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * ConfigItem：一条配置记录
 * (app, env, configKey) 唯一，确保同一应用同一环境下同一 key 只有一条配置
 */
@Entity
@Table(
        name = "config_item",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_app_env_key",
                columnNames = {"app", "env", "config_key"}
        )
)
public class ConfigItem {

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
     * - 表示配置变更的“代数”
     * - 客户端未来可用它做缓存更新、回退、审计
     */
    @Column(nullable = false)
    private long version;

    @Version
    private long lockVersion;

    @Column(nullable = false)
    private Instant updatedAt;

    // ====== Getter/Setter（JPA 需要） ======

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

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}