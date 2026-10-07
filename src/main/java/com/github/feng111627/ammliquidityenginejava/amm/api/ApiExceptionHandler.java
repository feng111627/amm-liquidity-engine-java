package com.github.feng111627.ammliquidityenginejava.amm.api;

import com.github.feng111627.ammliquidityenginejava.amm.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 把业务异常映射成可区分的 HTTP 响应 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PairNotFoundException.class)
    public ResponseEntity<Map<String, String>> pairNotFound(PairNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "PAIR_NOT_FOUND", "message", e.getMessage()));
    }

    @ExceptionHandler(PairAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> pairExists(PairAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "PAIR_ALREADY_EXISTS", "message", e.getMessage()));
    }

    @ExceptionHandler(InsufficientLiquidityException.class)
    public ResponseEntity<Map<String, String>> insufficient(InsufficientLiquidityException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "INSUFFICIENT_LIQUIDITY", "message", e.getMessage()));
    }

    @ExceptionHandler(SlippageExceededException.class)
    public ResponseEntity<Map<String, String>> slippage(SlippageExceededException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "SLIPPAGE_EXCEEDED", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
