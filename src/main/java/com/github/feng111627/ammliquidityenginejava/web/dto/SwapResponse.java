package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;

/**
 * 兑换结果。
 */
public record SwapResponse(String side,
                           String tokenIn,
                           String tokenOut,
                           BigDecimal amountIn,
                           BigDecimal amountOut,
                           BigDecimal feePaid,
                           BigDecimal reserveBaseAfter,
                           BigDecimal reserveQuoteAfter,
                           String requestId,
                           boolean replayed) {
}
