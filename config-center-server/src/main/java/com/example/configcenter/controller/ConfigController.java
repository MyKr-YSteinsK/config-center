package com.example.configcenter.controller;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.dto.request.UpsertConfigRequest;
import com.example.configcenter.dto.response.ConfigItemDto;
import com.example.configcenter.service.ConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/configs")
@Validated
public class ConfigController {

    private final ConfigService service;

    public ConfigController(ConfigService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<ConfigItemDto> upsert(@Valid @RequestBody UpsertConfigRequest req) {
        return ApiResponse.ok(service.upsert(req));
    }

    @GetMapping
    public ApiResponse<List<ConfigItemDto>> list(@RequestParam @NotBlank String app,
                                                 @RequestParam @NotBlank String env) {
        return ApiResponse.ok(service.list(app, env));
    }

    /**
     * {key:.+} 很重要：允许 key 里包含点号，比如 "db.pool.size"
     */
    @GetMapping("/{key:.+}")
    public ApiResponse<ConfigItemDto> getOne(@PathVariable String key,
                                             @RequestParam @NotBlank String app,
                                             @RequestParam @NotBlank String env) {
        return ApiResponse.ok(service.getOne(app, env, key));
    }
    @GetMapping("/history")
    public com.example.configcenter.dto.ApiResponse<java.util.List<com.example.configcenter.dto.response.ConfigHistoryDto>> history(
            @RequestParam @jakarta.validation.constraints.NotBlank String app,
            @RequestParam @jakarta.validation.constraints.NotBlank String env,
            @RequestParam @jakarta.validation.constraints.NotBlank String key) {
        return com.example.configcenter.dto.ApiResponse.ok(service.history(app, env, key));
    }
    @PostMapping("/rollback")
    public com.example.configcenter.dto.ApiResponse<com.example.configcenter.dto.response.ConfigItemDto> rollback(
            @jakarta.validation.Valid @RequestBody com.example.configcenter.dto.request.RollbackConfigRequest req) {
        return com.example.configcenter.dto.ApiResponse.ok(service.rollback(req));
    }
}