package com.github.feng111627.ammliquidityenginejava.amm.exception;

/** 交易对不存在 */
public class PairNotFoundException extends RuntimeException {
    public PairNotFoundException(String pairId) {
        super("交易对不存在: " + pairId);
    }
}
