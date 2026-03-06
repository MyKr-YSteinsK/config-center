package com.example.configcenter.controller;

import com.example.configcenter.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 最简单的活性检查接口。
 * 一方面确认服务活着，另一方面顺手看看统一响应和 traceId 有没有挂好。
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public ApiResponse<?> ping() {
        return ApiResponse.ok("pong @ " + Instant.now());
    }
}
