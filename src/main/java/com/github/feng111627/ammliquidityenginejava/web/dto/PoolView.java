package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;

/**
 * 资金池视图。
 */
public record PoolView(String base,
                       String quote,
                       BigDecimal reserveBase,
                       BigDecimal reserveQuote,
                       BigDecimal totalShares,
                       BigDecimal feeRate) {
}
