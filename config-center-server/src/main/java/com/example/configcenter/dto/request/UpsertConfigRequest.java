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
}