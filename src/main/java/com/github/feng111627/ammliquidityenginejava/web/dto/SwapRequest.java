package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;

/**
 * 兑换请求。
 * side=BUY_BASE 表示用 quote 买入 base；side=SELL_BASE 表示卖出 base 换 quote。
 * minAmountOut 为到账下限，低于则拒绝（SLIPPAGE_PROTECTION）。
 */
public record SwapRequest(String userId,
                          String base,
                          String quote,
                          String side,
                          BigDecimal amountIn,
                          BigDecimal minAmountOut,
                          String requestId) {
}
