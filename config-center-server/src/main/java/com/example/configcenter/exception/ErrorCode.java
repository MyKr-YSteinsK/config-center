package com.example.configcenter.exception;

/**
 * 统一错误码体系。
 * code=0 表示成功，4xxx 更偏调用问题，5xxx 更偏服务自身异常。
 */
public enum ErrorCode {

    PARAM_INVALID(4001, "参数错误"),
    NOT_FOUND(4041, "资源不存在"),
    CONFLICT(4091, "版本冲突"),
    RATE_LIMIT(4290, "Too Many Requests"),
    SYSTEM_ERROR(5000, "系统异常");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code) {
        this(code, null);
    }

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
