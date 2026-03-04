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

    @GetMapping("/evaluate")
    public ApiResponse<FeatureEvalResult> evaluate(@RequestParam @NotBlank String app,
                                                   @RequestParam @NotBlank String env,
                                                   @RequestParam @NotBlank String name,
                                                   @RequestParam @NotBlank String userId) {
        return ApiResponse.ok(service.evaluate(app, env, name, userId));
    }
}