package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;

/**
 * 注入流动性请求。minBase/minQuote 为可选的滑点保护下限；requestId 为可选幂等键。
 */
public record AddLiquidityRequest(String userId,
                                  String base,
                                  String quote,
                                  BigDecimal baseAmount,
                                  BigDecimal quoteAmount,
                                  BigDecimal minBase,
                                  BigDecimal minQuote,
                                  String requestId) {
}
