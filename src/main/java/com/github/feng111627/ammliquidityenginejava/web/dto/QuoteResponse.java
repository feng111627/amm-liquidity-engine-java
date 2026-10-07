package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;

/**
 * 行情预览（不落账）。
 */
public record QuoteResponse(String side,
                            String tokenIn,
                            String tokenOut,
                            BigDecimal amountIn,
                            BigDecimal amountOut,
                            BigDecimal feePaid,
                            BigDecimal feeRate) {
}
