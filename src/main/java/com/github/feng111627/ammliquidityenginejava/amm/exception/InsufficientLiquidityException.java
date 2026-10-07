package com.github.feng111627.ammliquidityenginejava.amm.exception;

/** 池子储备不足，兑换无法成交 */
public class InsufficientLiquidityException extends RuntimeException {
    public InsufficientLiquidityException(String message) {
        super(message);
    }
}
