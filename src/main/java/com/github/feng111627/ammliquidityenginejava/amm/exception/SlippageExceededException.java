package com.github.feng111627.ammliquidityenginejava.amm.exception;

/** 实际到账低于用户指定的最少到账数量（滑点保护触发） */
public class SlippageExceededException extends RuntimeException {
    public SlippageExceededException(String message) {
        super(message);
    }
}
