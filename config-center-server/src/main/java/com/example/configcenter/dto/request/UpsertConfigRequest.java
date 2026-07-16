package com.example.configcenter.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 配置新增/更新请求体。
 * 不直接暴露 Entity 给接口层，是个小习惯，但能少很多无意间把内部字段放出去的坑。
 */
public class UpsertConfigRequest {

    @NotBlank
    @Size(max = 100)
    private String app;

    @NotBlank
    @Size(max = 50)
    private String env;

    @NotBlank
    @Size(max = 200)
    private String key;

    @NotBlank
    @Size(max = 2000)
    private String value;

    @Size(max = 500)
    private String description;

    /**
     * 并发保护字段。
     * 调用方如果带了 expectedVersion，服务端就会校验“我准备修改的这份数据还是不是我刚看到的那一版”。
     */
    @Positive
    private Long expectedVersion;

    // 审计信息不是强制项，但有了它，历史记录会像人写出来的，而不是只有冷冰冰的一串版本号。
    @Size(max = 100)
    private String operator;

    @Size(max = 500)
    private String reason;

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

    public Long getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(Long expectedVersion) { this.expectedVersion = expectedVersion; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
