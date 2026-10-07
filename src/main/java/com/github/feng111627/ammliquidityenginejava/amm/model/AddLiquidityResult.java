package com.github.feng111627.ammliquidityenginejava.amm.model;

import java.math.BigInteger;

/** 注入流动性的结算结果 */
public record AddLiquidityResult(
        String pairId,
        String provider,
        BigInteger amountA,
        BigInteger amountB,
        BigInteger sharesMinted,
        BigInteger totalSharesAfter,
        BigInteger reserveAAfter,
        BigInteger reserveBAfter
) {}
