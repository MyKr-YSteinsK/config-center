package com.example.configcenter.controller;

import com.example.configcenter.config.TraceIdFilter;
import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.request.RollbackConfigRequest;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.response.ConfigHistoryDto;
import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.dto.response.ConfigWatchDto;
import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import com.example.configcenter.service.ApiKeyService;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.ConfigWatchNotifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api")
@Validated
public class ConfigController {

    private final ConfigService service;
    private final ConfigWatchNotifier notifier;
    private final ApiKeyService apiKeyService;

    public ConfigController(ConfigService service,
                            ConfigWatchNotifier notifier,
                            ApiKeyService apiKeyService) {

        this.service = service;
        this.notifier = notifier;
        this.apiKeyService = apiKeyService;
    }

    @PostMapping("/configs")
    public ApiResponse<ConfigItemDto> upsert(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @Valid @RequestBody UpsertConfigRequest req) {
        authorize(apiKey, req.getApp(), req.getEnv());
        return ApiResponse.ok(service.upsert(req));
    }

    // 配置列表支持 If-None-Match，这样客户端没命中更新时可以直接拿 304，省 body 也省流量。
    @GetMapping("/configs")
    public ResponseEntity<?> list(
            @RequestParam @NotBlank @Size(max = 100) String app,
            @RequestParam @NotBlank @Size(max = 50) String env,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        ConfigService.ConfigListSnapshot snapshot = service.listSnapshot(app, env);

        if (ifNoneMatch != null && ifNoneMatch.equals(snapshot.etag())) {
            // 304 按惯例不带 body，但 traceId 这种头信息还是会正常挂出去。
            return ResponseEntity.status(304).eTag(snapshot.etag()).build();
        }

        return ResponseEntity.ok()
                .eTag(snapshot.etag())
                .body(ApiResponse.ok(snapshot.data()));
    }

    /**
     * {key:.+} 这个写法不能省。
     * 不然像 db.pool.size 这种带点的 key，Spring 会把后半截当扩展名吃掉。
     */
    @GetMapping("/configs/{key:.+}")
    public ApiResponse<ConfigItemDto> getOne(@PathVariable @Size(max = 200) String key,
                                             @RequestParam @NotBlank @Size(max = 100) String app,
                                             @RequestParam @NotBlank @Size(max = 50) String env) {
        return ApiResponse.ok(service.getOne(app, env, key));
    }

    @GetMapping("/configs/history")
    public ApiResponse<List<ConfigHistoryDto>> history(
            @RequestParam @NotBlank @Size(max = 100) String app,
            @RequestParam @NotBlank @Size(max = 50) String env,
            @RequestParam @NotBlank @Size(max = 200) String key,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) @Positive Long beforeVersion) {
        return ApiResponse.ok(service.history(app, env, key, limit, beforeVersion));
    }

    @PostMapping("/configs/rollback")
    public ApiResponse<ConfigItemDto> rollback(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @Valid @RequestBody RollbackConfigRequest req) {
        authorize(apiKey, req.getApp(), req.getEnv());
        return ApiResponse.ok(service.rollback(req));
    }

    private void authorize(String apiKey, String app, String env) {
        if (!apiKeyService.allow(apiKey, app, env)) {
            throw new BizException(ErrorCode.FORBIDDEN, "API Key 无权限，当前 app/env 不允许写入");
        }
    }

    // watch 走长轮询：有更新就立即返回，没更新就先挂住，直到超时或者被通知唤醒。
    @GetMapping("/configs/watch")
    public DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> watch(
            @RequestParam @NotBlank @Size(max = 100) String app,
            @RequestParam @NotBlank @Size(max = 50) String env,
            @RequestParam @PositiveOrZero long sinceVersion,
            @RequestParam(defaultValue = "30") @Min(1) @Max(60) int timeoutSeconds,
            @RequestAttribute(TraceIdFilter.REQUEST_ATTRIBUTE) String traceId) {

        long latest = service.latestVersion(app, env);

        if (latest > sinceVersion) {
            // 版本已经变了，就别让客户端白等，直接回。
            DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> dr = new DeferredResult<>(0L);
            dr.setResult(ResponseEntity.ok(ApiResponse.ok(new ConfigWatchDto(true, latest), traceId)));
            return dr;
        }
        ConfigWatchNotifier.Registration registration =
                notifier.register(app, env, Duration.ofSeconds(timeoutSeconds), latest, traceId);

        if (registration.accepted()) {
            long latestAfterRegistration = service.latestVersion(app, env);
            if (latestAfterRegistration > sinceVersion) {
                notifier.completeChanged(registration, latestAfterRegistration);
            }
        }
        return registration.result();
    }
}
