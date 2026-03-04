package com.example.configcenter.controller;

import com.example.configcenter.dto.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 健康检查接口：证明服务可用 & 统一返回结构可用。
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public ApiResponse<?> ping() {
        return ApiResponse.ok("pong @ " + Instant.now());
    }
}