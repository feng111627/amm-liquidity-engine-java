package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;

/**
 * 赎回流动性请求：按 LP 份额比例取回两种储备。
 */
public record RemoveLiquidityRequest(String userId,
                                     String base,
                                     String quote,
                                     BigDecimal shares,
                                     String requestId) {
}
