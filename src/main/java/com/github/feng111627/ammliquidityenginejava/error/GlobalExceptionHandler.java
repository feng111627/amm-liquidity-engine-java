package com.github.feng111627.ammliquidityenginejava.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 业务异常 -> HTTP 响应。
 *
 * <p>不同业务拒绝原因使用不同 {@link ErrorCode}，HTTP 状态码也按语义区分，
 * 响应体固定为 {@code {"code": "...", "message": "..."}}。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handle(ApiException ex) {
        HttpStatus status = statusOf(ex.getErrorCode());
        log.warn("业务拒绝 code={} status={} message={}", ex.getErrorCode(), status.value(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(Map.of("code", ex.getErrorCode().name(), "message", ex.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        log.warn("请求参数无法解析：{}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("code", ErrorCode.INVALID_AMOUNT.name(), "message", "请求参数非法：" + ex.getMessage()));
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case PAIR_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PAIR_ALREADY_EXISTS, IDEMPOTENCY_KEY_CONFLICT, SAME_TOKEN_NOT_ALLOWED ->
                    HttpStatus.CONFLICT;
            case INSUFFICIENT_BALANCE, INSUFFICIENT_LIQUIDITY, INSUFFICIENT_POOL_RESERVE ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
            case SLIPPAGE_PROTECTION -> HttpStatus.CONFLICT;
            case INVALID_AMOUNT -> HttpStatus.BAD_REQUEST;
        };
    }
}
