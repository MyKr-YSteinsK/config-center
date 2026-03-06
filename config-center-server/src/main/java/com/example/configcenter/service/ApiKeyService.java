package com.example.configcenter.service;

import com.example.configcenter.config.ApiKeyProperties;
import org.springframework.stereotype.Service;

/**
 * 最小可用版 API Key 鉴权。
 * 先做到“谁能改哪个 app/env”这一层，已经比完全裸奔强不少。
 */
@Service
public class ApiKeyService {

    private final ApiKeyProperties props;

    public ApiKeyService(ApiKeyProperties props) {
        this.props = props;
    }

    public boolean allow(String apiKey, String app, String env) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }

        for (ApiKeyProperties.ApiKeyItem item : props.getApiKeys()) {
            if (item.getKey().equals(apiKey)
                    && item.getApp().equals(app)
                    && item.getEnv().equals(env)) {
                return true;
            }
        }

        return false;
    }
}
