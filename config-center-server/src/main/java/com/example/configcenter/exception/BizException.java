package com.example.configcenter.exception;

/**
 * 业务异常。
 * 这类异常是“我知道自己在抛什么”，所以全局异常处理时可以更体面地翻译成接口响应。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
