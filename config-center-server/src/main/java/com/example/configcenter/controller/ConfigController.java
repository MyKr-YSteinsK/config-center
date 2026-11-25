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
import com.example.configcenter.receipt.IdempotentWrites;
import com.example.configcenter.receipt.WriteIntent;
import com.example.configcenter.service.ApiKeyService;
import com.example.configcenter.service.ConfigService;
import com.example.configcenter.service.ConfigWatchNotifier;
import com.example.configcenter.validation.UnicodeName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
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
    private final IdempotentWrites writes;

    public ConfigController(ConfigService service,
                            ConfigWatchNotifier notifier,
                            ApiKeyService apiKeyService,
                            IdempotentWrites writes) {

        this.service = service;
        this.notifier = notifier;
        this.apiKeyService = apiKeyService;
        this.writes = writes;
    }

    @PostMapping("/configs")
    @Operation(parameters = @Parameter(name = IdempotentWrites.KEY, in = ParameterIn.HEADER,
            description = IdempotentWrites.KEY_DESCRIPTION, schema = @Schema(pattern = IdempotentWrites.KEY_PATTERN)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", useReturnTypeSchema = true,
            headers = @Header(name = IdempotentWrites.REPLAYED, description = "True for a replay, false for a new numbered write.",
                    schema = @Schema(type = "boolean")))
    public ResponseEntity<ApiResponse<ConfigItemDto>> upsert(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @Valid @RequestBody UpsertConfigRequest req, HttpServletRequest request) {
        authorize(apiKey, req.getApp(), req.getEnv());
        return writes.respond(request, () -> WriteIntent.of(req), ConfigItemDto.class, () -> service.upsert(req));
    }

    // 配置列表支持 If-None-Match，这样客户端没命中更新时可以直接拿 304，省 body 也省流量。
    @GetMapping("/configs")
    public ResponseEntity<?> list(
            @RequestParam @NotBlank @Size(max = 100) @UnicodeName String app,
            @RequestParam @NotBlank @Size(max = 50) @UnicodeName String env,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        ConfigService.ConfigListSnapshot snapshot = service.listSnapshot(app, env);

        // Every valid namespace has a list representation, including the empty list.
        // Spring MVC handles weak comparison and tag lists on the 200 response below.
        if (ifNoneMatch != null && "*".equals(ifNoneMatch.trim())) {
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
    public ApiResponse<ConfigItemDto> getOne(@PathVariable @Size(max = 200) @UnicodeName String key,
                                             @RequestParam @NotBlank @Size(max = 100) @UnicodeName String app,
                                             @RequestParam @NotBlank @Size(max = 50) @UnicodeName String env) {
        return ApiResponse.ok(service.getOne(app, env, key));
    }

    @GetMapping("/configs/history")
    public ApiResponse<List<ConfigHistoryDto>> history(
            @RequestParam @NotBlank @Size(max = 100) @UnicodeName String app,
            @RequestParam @NotBlank @Size(max = 50) @UnicodeName String env,
            @RequestParam @NotBlank @Size(max = 200) @UnicodeName String key,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) @Positive Long beforeVersion) {
        return ApiResponse.ok(service.history(app, env, key, limit, beforeVersion));
    }

    @PostMapping("/configs/rollback")
    @Operation(parameters = @Parameter(name = IdempotentWrites.KEY, in = ParameterIn.HEADER,
            description = IdempotentWrites.KEY_DESCRIPTION, schema = @Schema(pattern = IdempotentWrites.KEY_PATTERN)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", useReturnTypeSchema = true,
            headers = @Header(name = IdempotentWrites.REPLAYED, description = "True for a replay, false for a new numbered write.",
                    schema = @Schema(type = "boolean")))
    public ResponseEntity<ApiResponse<ConfigItemDto>> rollback(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @Valid @RequestBody RollbackConfigRequest req, HttpServletRequest request) {
        authorize(apiKey, req.getApp(), req.getEnv());
        return writes.respond(request, () -> WriteIntent.of(req), ConfigItemDto.class, () -> service.rollback(req));
    }

    private void authorize(String apiKey, String app, String env) {
        if (!apiKeyService.allow(apiKey, app, env)) {
            throw new BizException(ErrorCode.FORBIDDEN, "API Key 无权限，当前 app/env 不允许写入");
        }
    }

    // watch 走长轮询：有更新就立即返回，没更新就先挂住，直到超时或者被通知唤醒。
    @GetMapping("/configs/watch")
    public DeferredResult<ResponseEntity<ApiResponse<ConfigWatchDto>>> watch(
            @RequestParam @NotBlank @Size(max = 100) @UnicodeName String app,
            @RequestParam @NotBlank @Size(max = 50) @UnicodeName String env,
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
                notifier.register(
                        app, env, Duration.ofSeconds(timeoutSeconds), latest, sinceVersion, traceId);

        if (registration.accepted()) {
            long latestAfterRegistration = service.latestVersion(app, env);
            if (latestAfterRegistration > sinceVersion) {
                notifier.completeChanged(registration, latestAfterRegistration);
            }
        }
        return registration.result();
    }
}
