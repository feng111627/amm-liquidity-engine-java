package com.github.feng111627.ammliquidityenginejava.amm.model;

import java.math.BigInteger;

/** 赎回流动性的结算结果 */
public record RemoveLiquidityResult(
        String pairId,
        String provider,
        BigInteger sharesBurned,
        BigInteger amountAOut,
        BigInteger amountBOut,
        BigInteger totalSharesAfter,
        BigInteger reserveAAfter,
        BigInteger reserveBAfter
) {}
