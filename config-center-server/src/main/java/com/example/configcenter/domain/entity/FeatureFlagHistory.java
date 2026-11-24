package com.example.configcenter.domain.entity;

import com.example.configcenter.domain.converter.StringListJsonConverter;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * feature flag 的历史快照。
 * 这里保留每次修改后的完整样子，后面想回滚、排查“为什么这个人命中了灰度”时都更有底。
 */
@Entity
@Table(name = "feature_flag_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_ff_hist_owner_ver", columnNames = {"owner_id", "version"}))
public class FeatureFlagHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The current row's identity controls lookup. Original names below are audit data.
    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(nullable = false, length = 100)
    private String app;

    @Column(nullable = false, length = 50)
    private String env;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private int rolloutPercentage;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "allowlist_json", nullable = false, length = 4000)
    private List<String> allowlist = new ArrayList<>();

    @Column(nullable = false)
    private long version;

    @Column(nullable = false, length = 20)
    private String action;

    @Column(length = 100)
    private String operator;

    @Column(length = 600)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }

    public void setOwnerId(long ownerId) { this.ownerId = ownerId; }

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
        this.allowlist = (allowlist == null) ? new ArrayList<>() : new ArrayList<>(allowlist);
    }

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
