package com.example.configcenter.dto;

import java.util.Map;

/**
 * 字段级错误载体。
 * 比起一股脑回一句“参数错了”，把具体字段点出来，调接口的人会少很多猜测。
 */
public class ErrorResponse {

    private Map<String, String> fieldErrors;

    public ErrorResponse() {}

    public ErrorResponse(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }
}
