package com.github.feng111627.ammliquidityenginejava.error;

/**
 * 业务错误码。不同的拒绝场景使用不同 code，便于调用方区分原因。
 */
public enum ErrorCode {

    PAIR_NOT_FOUND,
    PAIR_ALREADY_EXISTS,
    SAME_TOKEN_NOT_ALLOWED,
    INVALID_AMOUNT,
    INSUFFICIENT_BALANCE,
    INSUFFICIENT_POOL_RESERVE,
    SLIPPAGE_PROTECTION,
    INSUFFICIENT_LIQUIDITY,
    IDEMPOTENCY_KEY_CONFLICT
}
