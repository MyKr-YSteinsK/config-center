package com.example.configcenter.domain.entity;

import com.example.configcenter.domain.converter.StringListJsonConverter;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条 feature flag 的当前状态。
 * (app, env, name) 唯一，避免同一个功能开关被重复定义得乱七八糟。
 */
@Entity
@Table(
        name = "feature_flag",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_app_env_name",
                columnNames = {"app", "env", "name"}
        )
)
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String app;

    @Column(nullable = false, length = 50)
    private String env;

    @Column(nullable = false, length = 200)
    private String name;

    // 总开关，false 时直接短路，灰度和白名单都不用往下看了。
    @Column(nullable = false)
    private boolean enabled;

    // 灰度百分比，0 到 100。
    @Column(nullable = false)
    private int rolloutPercentage;

    // 业务版本号，和配置项一样，支撑历史、回滚、并发校验这些能力。
    @Column(nullable = false)
    private long version;

    @Version
    private long lockVersion;

    /**
     * allowlist 先存成 JSON 字符串，代码里继续用 List<String>。
     * 这样可读性够好，也不会把 demo 的注意力全拖去关系表设计上。
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "allowlist_json", nullable = false, length = 4000)
    private List<String> allowlist = new ArrayList<>();

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }

    public String getApp() { return app; }
    public void setApp(String app) { this.app = app; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getRolloutPercentage() { return rolloutPercentage; }
    public void setRolloutPercentage(int rolloutPercentage) { this.rolloutPercentage = rolloutPercentage; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public List<String> getAllowlist() { return allowlist; }
    public void setAllowlist(List<String> allowlist) {
        this.allowlist = (allowlist == null) ? new ArrayList<>() : allowlist;
    }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
