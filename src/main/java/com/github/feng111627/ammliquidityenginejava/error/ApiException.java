package com.github.feng111627.ammliquidityenginejava.error;

/**
 * 可预期的业务异常，由全局异常处理器翻译为 HTTP 响应。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
