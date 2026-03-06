package com.example.configcenter.domain.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一条真实生效中的配置项。
 * (app, env, configKey) 唯一，保证同一个应用同一个环境下同一个 key 只有一份当前值。
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
     * 业务版本号，不是数据库主键。
     * 它会贯穿 ETag、回滚、历史审计、watch 等链路，算是这套 demo 的主心骨之一。
     */
    @Column(nullable = false)
    private long version;

    // JPA 的乐观锁版本，主要防并发写冲突。
    @Version
    private long lockVersion;

    @Column(nullable = false)
    private Instant updatedAt;

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
