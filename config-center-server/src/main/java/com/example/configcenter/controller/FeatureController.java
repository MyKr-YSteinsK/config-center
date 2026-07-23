package com.example.configcenter.controller;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.request.RollbackFeatureRequest;
import com.example.configcenter.dto.request.UpsertFeatureRequest;
import com.example.configcenter.dto.response.FeatureEvalResult;
import com.example.configcenter.dto.response.FeatureFlagDto;
import com.example.configcenter.dto.response.FeatureHistoryDto;
import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import com.example.configcenter.service.ApiKeyService;
import com.example.configcenter.service.FeatureFlagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/features")
@Validated
public class FeatureController {

    private final FeatureFlagService service;
    private final ApiKeyService apiKeyService;

    public FeatureController(FeatureFlagService service, ApiKeyService apiKeyService) {
        this.service = service;
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    public ApiResponse<FeatureFlagDto> upsert(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @Valid @RequestBody UpsertFeatureRequest req) {
        authorize(apiKey, req.getApp(), req.getEnv());
        return ApiResponse.ok(service.upsert(req));
    }

    @GetMapping
    public ApiResponse<List<FeatureFlagDto>> list(
            @RequestParam @NotBlank @Size(max = 100) String app,
            @RequestParam @NotBlank @Size(max = 50) String env) {
        return ApiResponse.ok(service.list(app, env));
    }

    // evaluate 接口除了告诉你 true/false，也是在对外暴露当前灰度规则的判断结果。
    @GetMapping("/evaluate")
    public ApiResponse<FeatureEvalResult> evaluate(
            @RequestParam @NotBlank @Size(max = 100) String app,
            @RequestParam @NotBlank @Size(max = 50) String env,
            @RequestParam @NotBlank @Size(max = 200) String name,
            @RequestParam @NotBlank @Size(max = 200) String userId) {
        return ApiResponse.ok(service.evaluate(app, env, name, userId));
    }

    @GetMapping("/history")
    public ApiResponse<List<FeatureHistoryDto>> history(
            @RequestParam @NotBlank @Size(max = 100) String app,
            @RequestParam @NotBlank @Size(max = 50) String env,
            @RequestParam @NotBlank @Size(max = 200) String name,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) @Positive Long beforeVersion) {
        return ApiResponse.ok(service.history(app, env, name, limit, beforeVersion));
    }

    // 这里的回滚思路和配置项一致：不是把旧记录改回来，而是生成一条新的当前版本。
    @PostMapping("/rollback")
    public ApiResponse<FeatureFlagDto> rollback(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @Valid @RequestBody RollbackFeatureRequest req) {
        authorize(apiKey, req.getApp(), req.getEnv());
        return ApiResponse.ok(service.rollback(req));
    }

    private void authorize(String apiKey, String app, String env) {
        if (!apiKeyService.allow(apiKey, app, env)) {
            throw new BizException(ErrorCode.FORBIDDEN, "API Key 无权限，当前 app/env 不允许写入");
        }
    }
}
