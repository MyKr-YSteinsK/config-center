package com.example.configcenter.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * feature 回滚请求，结构和配置回滚保持一致，调用体验会更统一。
 */
public class RollbackFeatureRequest {

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
    @Positive
    private Long targetVersion;

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

    public Long getTargetVersion() { return targetVersion; }
    public void setTargetVersion(Long targetVersion) { this.targetVersion = targetVersion; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
