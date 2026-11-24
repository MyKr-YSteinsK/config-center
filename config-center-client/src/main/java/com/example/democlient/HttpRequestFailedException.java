package com.example.democlient;

public class HttpRequestFailedException extends RuntimeException {

    private final Integer statusCode;
    private final FailureKind kind;

    public HttpRequestFailedException(String message, Integer statusCode, boolean cacheFallbackAllowed) {
        this(message, statusCode, cacheFallbackAllowed, null);
    }

    public HttpRequestFailedException(String message, boolean cacheFallbackAllowed, Throwable cause) {
        this(message, null, cacheFallbackAllowed, cause);
    }

    public HttpRequestFailedException(
            String message, Integer statusCode, boolean cacheFallbackAllowed, Throwable cause) {
        this(message, statusCode, cacheFallbackAllowed ? FailureKind.TRANSIENT : FailureKind.TERMINAL, cause);
    }

    private HttpRequestFailedException(String message, Integer statusCode, FailureKind kind, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.kind = kind;
    }

    static HttpRequestFailedException circuitOpen(String breakerSnapshot) {
        return new HttpRequestFailedException("CIRCUIT_OPEN: " + breakerSnapshot, null, FailureKind.CIRCUIT_OPEN, null);
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public boolean isCacheFallbackAllowed() {
        return kind == FailureKind.TRANSIENT;
    }

    boolean isRecoverable() {
        return kind != FailureKind.TERMINAL;
    }

    private enum FailureKind { TRANSIENT, CIRCUIT_OPEN, TERMINAL }
}
