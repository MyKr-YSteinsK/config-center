package com.example.configcenter.domain.entity;

import com.example.configcenter.domain.converter.StringListJsonConverter;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * FeatureFlag：特性开关
 * (app, env, name) 唯一：同一应用同一环境下同名 feature 只能一条
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

    /**
     * 总开关：false 时无条件关闭（优先级最高）
     */
    @Column(nullable = false)
    private boolean enabled;

    /**
     * 灰度百分比：0..100
     */
    @Column(nullable = false)
    private int rolloutPercentage;

    /**
     * allowlist 最简落库方式：存成 JSON 字符串（单列），代码里仍用 List<String>
     * demo 重点是“规则与分层”，先不拆表。
     */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "allowlist_json", nullable = false, length = 4000)
    private List<String> allowlist = new ArrayList<>();

    @Column(nullable = false)
    private Instant updatedAt;

    // ===== Getter/Setter =====

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

    public List<String> getAllowlist() { return allowlist; }
    public void setAllowlist(List<String> allowlist) {
        this.allowlist = (allowlist == null) ? new ArrayList<>() : allowlist;
    }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}