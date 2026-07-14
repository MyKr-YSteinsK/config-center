package com.example.democlient;

public class HttpRequestFailedException extends RuntimeException {

    private final Integer statusCode;
    private final boolean cacheFallbackAllowed;

    public HttpRequestFailedException(String message, Integer statusCode, boolean cacheFallbackAllowed) {
        super(message);
        this.statusCode = statusCode;
        this.cacheFallbackAllowed = cacheFallbackAllowed;
    }

    public HttpRequestFailedException(String message, boolean cacheFallbackAllowed, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.cacheFallbackAllowed = cacheFallbackAllowed;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isCacheFallbackAllowed() {
        return cacheFallbackAllowed;
    }
}
