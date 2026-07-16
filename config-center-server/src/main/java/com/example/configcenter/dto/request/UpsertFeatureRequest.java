package com.example.configcenter.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * feature 新增/更新请求体。
 * rolloutPercentage 限定在 0..100，是为了把灰度规则控制在最朴素也最容易解释的范围里。
 */
public class UpsertFeatureRequest {

    @NotBlank
    @Size(max = 100)
    private String app;

    @NotBlank
    @Size(max = 50)
    private String env;

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotNull
    private Boolean enabled;

    @NotNull
    @Min(0)
    @Max(100)
    private Integer rolloutPercentage;

    @Size(max = 20)
    private List<@NotBlank @Size(max = 32) String> allowlist;

    @Positive
    private Long expectedVersion;

    @Size(max = 100)
    private String operator;

    @Size(max = 500)
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
