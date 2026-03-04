package com.example.configcenter.dto;

import java.util.Map;

/**
 * 用于承载字段级错误（field -> message）
 * 让调用者能明确知道是哪个字段不对
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