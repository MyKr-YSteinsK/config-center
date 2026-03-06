package com.example.configcenter.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * feature 新增/更新请求体。
 * rolloutPercentage 限定在 0..100，是为了把灰度规则控制在最朴素也最容易解释的范围里。
 */
public class UpsertFeatureRequest {

    @NotBlank
    private String app;

    @NotBlank
    private String env;

    @NotBlank
    private String name;

    @NotNull
    private Boolean enabled;

    @NotNull
    @Min(0)
    @Max(100)
    private Integer rolloutPercentage;

    private List<String> allowlist;

    private Long expectedVersion;

    private String operator;
    private String reason;

    public String getApp() { return app; }
    public void setApp(String app) { this.app = app; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getRolloutPercentage() { return rolloutPercentage; }
    public void setRolloutPercentage(Integer rolloutPercentage) { this.rolloutPercentage = rolloutPercentage; }

    public List<String> getAllowlist() { return allowlist; }
    public void setAllowlist(List<String> allowlist) { this.allowlist = allowlist; }

    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
