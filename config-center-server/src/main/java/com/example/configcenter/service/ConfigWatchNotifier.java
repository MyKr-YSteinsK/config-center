package com.example.configcenter.service;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.response.ConfigWatchDto;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ConfigWatchNotifier {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<DeferredResult<ApiResponse<ConfigWatchDto>>>> waits =
            new ConcurrentHashMap<>();

    private static String key(String app, String env) {
        return app + "|" + env;
    }

    public DeferredResult<ApiResponse<ConfigWatchDto>> register(String app, String env, Duration timeout, long latestVersion) {
        DeferredResult<ApiResponse<ConfigWatchDto>> dr = new DeferredResult<>(timeout.toMillis());

        // 超时：返回 changed=false（保持统一响应结构，不用 204）
        dr.onTimeout(() -> dr.setResult(ApiResponse.ok(new ConfigWatchDto(false, latestVersion))));
        dr.onCompletion(() -> {
            List<DeferredResult<ApiResponse<ConfigWatchDto>>> list = waits.get(key(app, env));
            if (list != null) list.remove(dr);
        });

        waits.computeIfAbsent(key(app, env), k -> new CopyOnWriteArrayList<>()).add(dr);
        return dr;
    }

    public void notifyChanged(String app, String env, long latestVersion) {
        List<DeferredResult<ApiResponse<ConfigWatchDto>>> list = waits.remove(key(app, env));
        if (list == null || list.isEmpty()) return;

        ApiResponse<ConfigWatchDto> payload = ApiResponse.ok(new ConfigWatchDto(true, latestVersion));
        for (DeferredResult<ApiResponse<ConfigWatchDto>> dr : list) {
            dr.setResult(payload);
        }
    }
}