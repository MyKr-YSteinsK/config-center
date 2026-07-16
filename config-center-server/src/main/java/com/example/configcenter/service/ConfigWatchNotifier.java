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

    private final ConcurrentHashMap<NamespaceKey, CopyOnWriteArrayList<Waiter>> waits =
            new ConcurrentHashMap<>();

    public DeferredResult<ApiResponse<ConfigWatchDto>> register(
            String app, String env, Duration timeout, long latestVersion, String traceId) {
        NamespaceKey namespace = new NamespaceKey(app, env);
        DeferredResult<ApiResponse<ConfigWatchDto>> dr = new DeferredResult<>(timeout.toMillis());
        Waiter waiter = new Waiter(namespace, traceId, dr);

        // 超时不算异常，明确回一个 changed=false，客户端就知道这轮只是“没等到新消息”。
        dr.onTimeout(() -> dr.setResult(
                ApiResponse.ok(new ConfigWatchDto(false, latestVersion), waiter.traceId())));
        dr.onCompletion(() -> remove(waiter));

        waits.compute(namespace, (key, list) -> {
            CopyOnWriteArrayList<Waiter> current =
                    list == null ? new CopyOnWriteArrayList<>() : list;
            current.add(waiter);
            return current;
        });
        return dr;
    }

    public void notifyChanged(String app, String env, long latestVersion) {
        List<Waiter> list = waits.remove(new NamespaceKey(app, env));
        if (list == null || list.isEmpty()) return;

        for (Waiter waiter : list) {
            waiter.result().setResult(ApiResponse.ok(
                    new ConfigWatchDto(true, latestVersion), waiter.traceId()));
        }
    }

    public int pendingNamespaceCount() {
        return waits.size();
    }

    private void remove(Waiter waiter) {
        waits.computeIfPresent(waiter.namespace(), (key, list) -> {
            list.remove(waiter);
            return list.isEmpty() ? null : list;
        });
    }

    private record NamespaceKey(String app, String env) {}

    private record Waiter(
            NamespaceKey namespace,
            String traceId,
            DeferredResult<ApiResponse<ConfigWatchDto>> result) {}
}
