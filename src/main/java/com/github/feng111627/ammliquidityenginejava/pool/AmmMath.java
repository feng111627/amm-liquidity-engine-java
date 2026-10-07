package com.github.feng111627.ammliquidityenginejava.pool;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 恒定乘积 AMM 定价数学：x * y = k。
 *
 * <p>兑换公式（输入侧收固定比例手续费，手续费留在池内）：</p>
 * <pre>
 *   amountInWithFee = amountIn * (1 - feeRate)
 *   amountOut       = reserveOut * amountInWithFee
 *                     / (reserveIn + amountInWithFee)
 * </pre>
 * 除法一律向下取整（FLOOR），保证池不会被向上取整“多发”。
 */
public final class AmmMath {

    /** 除法精度：18 位小数，足够模拟常见代币精度，同时向下取整保护池子。 */
    public static final int SCALE = 18;
    public static final RoundingMode ROUNDING = RoundingMode.FLOOR;

    /** 初始流动性死锁量（同 Uniswap MINIMUM_LIQUIDITY），锁在零地址，永久不可赎回。 */
    public static final BigDecimal MINIMUM_LIQUIDITY = new BigDecimal("0.000000000000001");

    private AmmMath() {
    }

    /**
     * 给定输入数量与两侧储备，计算输出数量（FLOOR）。
     *
     * @param feeRate 手续费比例，如 0.003 表示 0.3%
     */
    public static BigDecimal outputAmount(BigDecimal inputAmount,
                                          BigDecimal reserveIn,
                                          BigDecimal reserveOut,
                                          BigDecimal feeRate) {
        BigDecimal amountInWithFee = inputAmount.multiply(BigDecimal.ONE.subtract(feeRate))
                .setScale(SCALE, ROUNDING);
        BigDecimal numerator = amountInWithFee.multiply(reserveOut);
        BigDecimal denominator = reserveIn.add(amountInWithFee);
        return numerator.divide(denominator, SCALE, ROUNDING);
    }

    /**
     * 首次注入时的 LP 份额：floor(sqrt(a*b)) - MINIMUM_LIQUIDITY。
     * 总份额 = floor(sqrt(a*b))，其中 MINIMUM_LIQUIDITY 份永久锁死。
     *
     * @return 长度 2 数组：[mintableShares, totalShares]
     */
    public static BigDecimal[] initialShares(BigDecimal baseIn, BigDecimal quoteIn) {
        BigDecimal product = baseIn.multiply(quoteIn);
        BigDecimal sqrt = sqrt(product);
        BigDecimal total = sqrt.setScale(SCALE, ROUNDING);
        BigDecimal mintable = total.subtract(MINIMUM_LIQUIDITY);
        if (mintable.signum() <= 0) {
            mintable = BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        }
        return new BigDecimal[]{mintable, total};
    }

    /**
     * 已有流动性时注入，按“更吃亏”的一侧计算份额，保证两种储备的注入比例
     * 不会稀释既有 LP：
     * <pre>
     *   shares = min(baseIn/reserveBase, quoteIn/reserveQuote) * totalShares
     * </pre>
     */
    public static BigDecimal addShares(BigDecimal baseIn,
                                       BigDecimal quoteIn,
                                       BigDecimal reserveBase,
                                       BigDecimal reserveQuote,
                                       BigDecimal totalShares) {
        BigDecimal byBase = baseIn.multiply(totalShares).divide(reserveBase, SCALE, ROUNDING);
        BigDecimal byQuote = quoteIn.multiply(totalShares).divide(reserveQuote, SCALE, ROUNDING);
        return byBase.min(byQuote).setScale(SCALE, ROUNDING);
    }

    /**
     * 赎回时按份额比例取回两种储备（FLOOR），池不会被多发。
     */
    public static BigDecimal redeemAmount(BigDecimal shares,
                                          BigDecimal reserve,
                                          BigDecimal totalShares) {
        return shares.multiply(reserve).divide(totalShares, SCALE, ROUNDING);
    }

    /**
     * BigDecimal 平方根，结果向下取整到 18 位小数。
     */
    static BigDecimal sqrt(BigDecimal value) {
        if (value.signum() < 0) {
            throw new ArithmeticException("sqrt of negative");
        }
        if (value.signum() == 0) {
            return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        }
        return value.sqrt(MathContext.DECIMAL128).setScale(SCALE, ROUNDING);
    }
}
