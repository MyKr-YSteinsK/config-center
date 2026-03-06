package com.example.configcenter.controller;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.request.UpsertFeatureRequest;
import com.example.configcenter.dto.response.FeatureEvalResult;
import com.example.configcenter.dto.response.FeatureFlagDto;
import com.example.configcenter.service.FeatureFlagService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/features")
@Validated
public class FeatureController {

    private final FeatureFlagService service;

    public FeatureController(FeatureFlagService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<FeatureFlagDto> upsert(@Valid @RequestBody UpsertFeatureRequest req) {
        return ApiResponse.ok(service.upsert(req));
    }

    @GetMapping
    public ApiResponse<List<FeatureFlagDto>> list(@RequestParam @NotBlank String app,
                                                  @RequestParam @NotBlank String env) {
        return ApiResponse.ok(service.list(app, env));
    }

    // evaluate 接口除了告诉你 true/false，也是在对外暴露当前灰度规则的判断结果。
    @GetMapping("/evaluate")
    public ApiResponse<FeatureEvalResult> evaluate(@RequestParam @NotBlank String app,
                                                   @RequestParam @NotBlank String env,
                                                   @RequestParam @NotBlank String name,
                                                   @RequestParam @NotBlank String userId) {
        return ApiResponse.ok(service.evaluate(app, env, name, userId));
    }

    @GetMapping("/history")
    public com.example.configcenter.dto.ApiResponse<java.util.List<com.example.configcenter.dto.response.FeatureHistoryDto>> history(
            @RequestParam @NotBlank String app,
            @RequestParam @NotBlank String env,
            @RequestParam @NotBlank String name) {
        return com.example.configcenter.dto.ApiResponse.ok(service.history(app, env, name));
    }

    // 这里的回滚思路和配置项一致：不是把旧记录改回来，而是生成一条新的当前版本。
    @PostMapping("/rollback")
    public com.example.configcenter.dto.ApiResponse<com.example.configcenter.dto.response.FeatureFlagDto> rollback(
            @Valid @RequestBody com.example.configcenter.dto.request.RollbackFeatureRequest req) {
        return com.example.configcenter.dto.ApiResponse.ok(service.rollback(req));
    }
}
