package com.example.configcenter.service;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.response.ConfigWatchDto;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * watch 长轮询的等待队列。
 * 谁在等配置变更，就先把 DeferredResult 挂在这里，等配置提交成功后统一唤醒。
 */
@Component
public class ConfigWatchNotifier {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<DeferredResult<ApiResponse<ConfigWatchDto>>>> waits =
            new ConcurrentHashMap<>();

    private static String key(String app, String env) {
        return app + "|" + env;
    }

    public DeferredResult<ApiResponse<ConfigWatchDto>> register(String app, String env, Duration timeout, long latestVersion) {
        DeferredResult<ApiResponse<ConfigWatchDto>> dr = new DeferredResult<>(timeout.toMillis());

        // 超时不算异常，明确回一个 changed=false，客户端就知道这轮只是“没等到新消息”。
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
