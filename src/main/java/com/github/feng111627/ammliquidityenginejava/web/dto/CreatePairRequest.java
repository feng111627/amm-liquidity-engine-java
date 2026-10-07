package com.github.feng111627.ammliquidityenginejava.web.dto;

/**
 * 创建交易对请求。
 */
public record CreatePairRequest(String base, String quote) {
}
