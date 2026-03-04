package com.example.configcenter.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Controller 接收的请求体（不要直接用 Entity 当请求体，这是工程习惯）
 */
public class UpsertConfigRequest {

    @NotBlank
    private String app;

    @NotBlank
    private String env;

    @NotBlank
    private String key;

    @NotBlank
    private String value;

    private String description;

    public String getApp() { return app; }
    public void setApp(String app) { this.app = app; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    /**
     * 并发安全：
     * - 如果传了 expectedVersion，服务端会检查“当前 version 是否等于它”
     * - 不等则返回 409（避免丢更新）
     */
    private Long expectedVersion;

    /**
     * 审计字段（可选，但企业里基本都会要求）
     */
    private String operator;
    private String reason;

    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}