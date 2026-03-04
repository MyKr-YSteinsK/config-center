package com.example.configcenter.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class RollbackConfigRequest {

    @NotBlank
    private String app;

    @NotBlank
    private String env;

    @NotBlank
    private String key;

    @NotNull
    private Long targetVersion;

    private String operator;
    private String reason;

    public String getApp() { return app; }
    public void setApp(String app) { this.app = app; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public Long getTargetVersion() { return targetVersion; }
    public void setTargetVersion(Long targetVersion) { this.targetVersion = targetVersion; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}