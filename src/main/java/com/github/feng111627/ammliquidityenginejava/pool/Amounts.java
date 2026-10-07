package com.github.feng111627.ammliquidityenginejava.pool;

import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;

import java.math.BigDecimal;

/**
 * 金额入参校验与规范化工具。
 */
public final class Amounts {

    private Amounts() {
    }

    /** 必须为非空且严格为正，规范化到 18 位小数（向下截断）。 */
    public static BigDecimal requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    field + " 必须为正数，实际为：" + (value == null ? "null" : value.toPlainString()));
        }
        return value.setScale(AmmMath.SCALE, AmmMath.ROUNDING);
    }

    /** 可选下限：为空视为 0。 */
    public static BigDecimal optionalMin(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(AmmMath.SCALE);
        }
        if (value.signum() < 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    "数量下限不能为负数：" + value.toPlainString());
        }
        return value.setScale(AmmMath.SCALE, AmmMath.ROUNDING);
    }
}
