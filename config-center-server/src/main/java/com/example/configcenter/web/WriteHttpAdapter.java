package com.example.configcenter.web;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import com.example.configcenter.receipt.IdempotentWrites;
import com.example.configcenter.receipt.WriteIntent;
import com.example.configcenter.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public class WriteHttpAdapter {
    public static final String KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotency-Replayed";
    public static final String KEY_PATTERN = "[A-Za-z0-9._-]{1,128}";
    public static final String KEY_DESCRIPTION = "Optional request identifier within an exact app/env. Reuse it with the same body to retry.";
    private static final Pattern VALID_KEY = Pattern.compile(KEY_PATTERN);

    private final ApiKeyService apiKeys;
    private final IdempotentWrites numberedWrites;

    public WriteHttpAdapter(ApiKeyService apiKeys, IdempotentWrites numberedWrites) {
        this.apiKeys = apiKeys;
        this.numberedWrites = numberedWrites;
    }

    // MVC has already validated the body before a controller delegates its write here.
    public <T> ResponseEntity<ApiResponse<T>> respond(HttpServletRequest request, String apiKey, String app, String env,
                                                     Supplier<WriteIntent> intent, Class<T> type, Supplier<T> write) {
        if (!apiKeys.allow(apiKey, app, env)) {
            throw new BizException(ErrorCode.FORBIDDEN, "API Key 无权限，当前 app/env 不允许写入");
        }

        var headers = request.getHeaders(KEY);
        if (!headers.hasMoreElements()) {
            return ResponseEntity.ok(ApiResponse.ok(write.get()));
        }
        String key = headers.nextElement();
        if (headers.hasMoreElements() || !VALID_KEY.matcher(key).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Idempotency-Key 格式无效");
        }

        WriteIntent requested = intent.get();
        if (!app.equals(requested.app()) || !env.equals(requested.env())) {
            throw new IllegalStateException("Write intent must use the authorized namespace");
        }
        var result = numberedWrites.execute(requested, key.getBytes(StandardCharsets.US_ASCII), type, write);
        return ResponseEntity.ok().header(REPLAYED, Boolean.toString(result.replayed()))
                .body(ApiResponse.ok(result.data()));
    }
}
