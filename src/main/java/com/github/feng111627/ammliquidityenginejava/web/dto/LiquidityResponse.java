package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;

/**
 * 流动性操作结果（注入 ADD / 赎回 REMOVE 共用）。
 */
public record LiquidityResponse(String type,
                                String userId,
                                String base,
                                String quote,
                                BigDecimal shares,
                                BigDecimal totalShares,
                                BigDecimal baseAmount,
                                BigDecimal quoteAmount,
                                BigDecimal reserveBaseAfter,
                                BigDecimal reserveQuoteAfter,
                                String requestId,
                                boolean replayed) {
}
