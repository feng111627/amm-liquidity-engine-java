package com.github.feng111627.ammliquidityenginejava.web.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 账户余额视图。
 */
public record AccountView(String userId, Map<String, BigDecimal> balances) {
}
