package com.github.feng111627.ammliquidityenginejava.amm.model;

import java.math.BigInteger;

/** 一笔兑换的成交结果 */
public record SwapResult(
        String pairId,
        String tokenIn,
        String tokenOut,
        BigInteger amountIn,
        BigInteger amountOut,
        BigInteger feeAmount,
        BigInteger reserveAAfter,
        BigInteger reserveBAfter
) {}
