package com.example.configcenter.controller;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import com.example.configcenter.service.ApiKeyService;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.ConfigWatchNotifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
        if (!apiKeyService.allow(apiKey, req.getApp(), req.getEnv())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "API Key 无权限，当前 app/env 不允许写入");
        }
        return ApiResponse.ok(service.upsert(req));
    }

    // 配置列表支持 If-None-Match，这样客户端没命中更新时可以直接拿 304，省 body 也省流量。
    @GetMapping("/configs")
    public org.springframework.http.ResponseEntity<?> list(
            @RequestParam @NotBlank String app,
            @RequestParam @NotBlank String env,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        String etag = service.etagForList(app, env);

        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            // 304 按惯例不带 body，但 traceId 这种头信息还是会正常挂出去。
            return org.springframework.http.ResponseEntity.status(304).eTag(etag).build();
        }

        java.util.List<com.example.configcenter.dto.response.ConfigItemDto> data = service.list(app, env);
        return org.springframework.http.ResponseEntity.ok().eTag(etag).body(com.example.configcenter.dto.ApiResponse.ok(data));
    }

    /**
     * {key:.+} 这个写法不能省。
     * 不然像 db.pool.size 这种带点的 key，Spring 会把后半截当扩展名吃掉。
     */
    @GetMapping("/configs/{key:.+}")
    public ApiResponse<ConfigItemDto> getOne(@PathVariable String key,
                                             @RequestParam @NotBlank String app,
                                             @RequestParam @NotBlank String env) {
        return ApiResponse.ok(service.getOne(app, env, key));
    }

    @GetMapping("/configs/history")
    public com.example.configcenter.dto.ApiResponse<java.util.List<com.example.configcenter.dto.response.ConfigHistoryDto>> history(
            @RequestParam @jakarta.validation.constraints.NotBlank String app,
            @RequestParam @jakarta.validation.constraints.NotBlank String env,
            @RequestParam @jakarta.validation.constraints.NotBlank String key) {
        return com.example.configcenter.dto.ApiResponse.ok(service.history(app, env, key));
    }

    @PostMapping("/configs/rollback")
    public com.example.configcenter.dto.ApiResponse<com.example.configcenter.dto.response.ConfigItemDto> rollback(
            @jakarta.validation.Valid @RequestBody com.example.configcenter.dto.request.RollbackConfigRequest req) {
        return com.example.configcenter.dto.ApiResponse.ok(service.rollback(req));
    }

    // watch 走长轮询：有更新就立即返回，没更新就先挂住，直到超时或者被通知唤醒。
    @GetMapping("/configs/watch")
    public org.springframework.web.context.request.async.DeferredResult<com.example.configcenter.dto.ApiResponse<com.example.configcenter.dto.response.ConfigWatchDto>> watch(
            @RequestParam @jakarta.validation.constraints.NotBlank String app,
            @RequestParam @jakarta.validation.constraints.NotBlank String env,
            @RequestParam long sinceVersion,
            @RequestParam(defaultValue = "30") int timeoutSeconds) {

        long latest = service.latestVersion(app, env);

        if (latest > sinceVersion) {
            // 版本已经变了，就别让客户端白等，直接回。
            org.springframework.web.context.request.async.DeferredResult<com.example.configcenter.dto.ApiResponse<com.example.configcenter.dto.response.ConfigWatchDto>> dr =
                    new org.springframework.web.context.request.async.DeferredResult<>(0L);
            dr.setResult(com.example.configcenter.dto.ApiResponse.ok(new com.example.configcenter.dto.response.ConfigWatchDto(true, latest)));
            return dr;
        }
        return notifier.register(app, env, java.time.Duration.ofSeconds(timeoutSeconds), latest);
    }
}
