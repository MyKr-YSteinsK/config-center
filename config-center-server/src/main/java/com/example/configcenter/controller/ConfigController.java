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
    public org.springframework.http.ResponseEntity<?> list(
            @RequestParam @NotBlank String app,
            @RequestParam @NotBlank String env,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        String etag = service.etagForList(app, env);

        if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
            // 304 通常不带 body（节省带宽），但响应头仍会有 traceId（由 Filter 注入）
            return org.springframework.http.ResponseEntity.status(304).eTag(etag).build();
        }

        java.util.List<com.example.configcenter.dto.response.ConfigItemDto> data = service.list(app, env);
        return org.springframework.http.ResponseEntity.ok().eTag(etag).body(com.example.configcenter.dto.ApiResponse.ok(data));
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