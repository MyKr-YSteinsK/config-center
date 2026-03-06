package com.example.configcenter.dto;

import org.slf4j.MDC;

/**
 * 统一响应外壳。
 * 成功和失败都走它，前端或调用方就不用一会儿猜这个接口返回 data，一会儿又猜它是不是直接丢字符串。
 */
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public ApiResponse() {}

    public ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "OK", data, MDC.get("traceId"));
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, MDC.get("traceId"));
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
