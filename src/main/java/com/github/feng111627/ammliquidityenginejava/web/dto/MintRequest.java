package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;

/**
 * 测试水龙头：给本地账户铸造代币（无真实发行，仅用于本地验证）。
 */
public record MintRequest(String userId, String token, BigDecimal amount) {
}
