package com.example.configcenter.exception;

/**
 * 统一错误码体系：
 * - code=0 成功
 * - 4xxx 参数/资源/冲突类
 * - 5xxx 系统类
 */
public enum ErrorCode {
    PARAM_INVALID(4001),
    NOT_FOUND(4041),
    CONFLICT(4091),
    SYSTEM_ERROR(5000);

    private final int code;

    ErrorCode(int code) { this.code = code; }

    public int code() { return code; }
}