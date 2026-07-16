package com.example.configcenter.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "security")
@Validated
public class ApiKeyProperties {

    // 这里直接把 yml 里的 security.api-keys 绑定成列表，后面鉴权时查起来很顺手。
    @NotNull
    @Valid
    private List<ApiKeyItem> apiKeys = new ArrayList<>();

    public List<ApiKeyItem> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<ApiKeyItem> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public static class ApiKeyItem {

        // 真实项目里当然不会明文放配置，这里为了 demo 先把完整链路走通。
        @NotBlank
        private String key;

        @NotBlank
        @Size(max = 100)
        private String app;

        @NotBlank
        @Size(max = 50)
        private String env;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getApp() {
            return app;
        }

        public void setApp(String app) {
            this.app = app;
        }

        public String getEnv() {
            return env;
        }

        public void setEnv(String env) {
            this.env = env;
        }
    }
}
