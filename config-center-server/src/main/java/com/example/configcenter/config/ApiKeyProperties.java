package com.example.configcenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "security")
public class ApiKeyProperties {

    private List<ApiKeyItem> apiKeys = new ArrayList<>();

    public List<ApiKeyItem> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<ApiKeyItem> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public static class ApiKeyItem {

        private String key;
        private String app;
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