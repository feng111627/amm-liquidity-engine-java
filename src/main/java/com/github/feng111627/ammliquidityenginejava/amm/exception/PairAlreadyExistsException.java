package com.github.feng111627.ammliquidityenginejava.amm.exception;

/** 交易对已存在 */
public class PairAlreadyExistsException extends RuntimeException {
    public PairAlreadyExistsException(String pairId) {
        super("交易对已存在: " + pairId);
    }
}
