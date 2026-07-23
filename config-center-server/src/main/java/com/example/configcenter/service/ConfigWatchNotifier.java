package com.example.configcenter.service;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.response.ConfigWatchDto;
import com.example.configcenter.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Watch 长轮询的有界等待队列。
 */
@Component
public class ConfigWatchNotifier {

    private final ConfigWatchProperties properties;
    private final Object monitor = new Object();
    private final Map<NamespaceKey, List<Waiter>> waits = new HashMap<>();
    private final LongAdder rejectedCount = new LongAdder();
    private int pendingWaiterCount;

    public ConfigWatchNotifier(ConfigWatchProperties properties) {
        this.properties = properties;
    }

    public Registration register(
            String app, String env, Duration timeout, long latestVersion, String traceId) {
        NamespaceKey namespace = new NamespaceKey(app, env);
        DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> result =
                new DeferredResult<>(timeout.toMillis());
        Waiter waiter = new Waiter(namespace, traceId, result);

        // 超时不是异常，明确回一个 changed=false，客户端就知道这轮只是“没等到新消息”。
        result.onTimeout(() -> complete(waiter, false, latestVersion));
        result.onCompletion(() -> remove(waiter));

        synchronized (monitor) {
            List<Waiter> namespaceWaiters = waits.get(namespace);
            if (pendingWaiterCount >= properties.getMaxPendingWaiters()
                    || (namespaceWaiters != null
                    && namespaceWaiters.size() >= properties.getMaxPendingPerNamespace())) {
                rejectedCount.increment();
                result.setResult(tooManyPendingWaiters(traceId));
                return Registration.rejected(result);
            }

            if (namespaceWaiters == null) {
                namespaceWaiters = new ArrayList<>();
                waits.put(namespace, namespaceWaiters);
            }
            namespaceWaiters.add(waiter);
            pendingWaiterCount++;
        }
        return Registration.accepted(result, waiter);
    }

    public void notifyChanged(String app, String env, long latestVersion) {
        List<Waiter> waiters;
        synchronized (monitor) {
            waiters = waits.remove(new NamespaceKey(app, env));
            if (waiters == null || waiters.isEmpty()) {
                return;
            }
            pendingWaiterCount -= waiters.size();
        }

        for (Waiter waiter : waiters) {
            waiter.result().setResult(successResponse(true, latestVersion, waiter.traceId()));
        }
    }

    public void completeChanged(Registration registration, long latestVersion) {
        if (registration.accepted()) {
            complete(registration.waiter(), true, latestVersion);
        }
    }

    public int pendingWaiterCount() {
        synchronized (monitor) {
            return pendingWaiterCount;
        }
    }

    public int pendingNamespaceCount() {
        synchronized (monitor) {
            return waits.size();
        }
    }

    public long rejectedCount() {
        return rejectedCount.sum();
    }

    private void complete(Waiter waiter, boolean changed, long latestVersion) {
        remove(waiter);
        waiter.result().setResult(successResponse(changed, latestVersion, waiter.traceId()));
    }

    private ResponseEntity<ApiResponse<ConfigWatchDto>> successResponse(
            boolean changed, long latestVersion, String traceId) {
        return ResponseEntity.ok(ApiResponse.ok(new ConfigWatchDto(changed, latestVersion), traceId));
    }

    private ResponseEntity<ApiResponse<ConfigWatchDto>> tooManyPendingWaiters(String traceId) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiResponse<>(
                        ErrorCode.RATE_LIMIT.getCode(),
                        ErrorCode.RATE_LIMIT.getDefaultMessage(),
                        null,
                        traceId));
    }

    private void remove(Waiter waiter) {
        synchronized (monitor) {
            List<Waiter> namespaceWaiters = waits.get(waiter.namespace());
            if (namespaceWaiters == null || !namespaceWaiters.remove(waiter)) {
                return;
            }
            pendingWaiterCount--;
            if (namespaceWaiters.isEmpty()) {
                waits.remove(waiter.namespace());
            }
        }
    }

    private record NamespaceKey(String app, String env) {}

    private record Waiter(
            NamespaceKey namespace,
            String traceId,
            DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> result) {}

    public static final class Registration {

        private final DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> result;
        private final Waiter waiter;
        private final boolean accepted;

        private Registration(
                DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> result,
                Waiter waiter,
                boolean accepted) {
            this.result = result;
            this.waiter = waiter;
            this.accepted = accepted;
        }

        private static Registration accepted(
                DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> result, Waiter waiter) {
            return new Registration(result, waiter, true);
        }

        private static Registration rejected(
                DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> result) {
            return new Registration(result, null, false);
        }

        public DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> result() {
            return result;
        }

        public boolean accepted() {
            return accepted;
        }

        private Waiter waiter() {
            return waiter;
        }
    }
}
