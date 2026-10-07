package com.github.feng111627.ammliquidityenginejava.tests;

import com.github.feng111627.ammliquidityenginejava.pool.AmmMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恒定乘积定价与 LP 份额数学测试。
 */
@DisplayName("业务一：AMM 定价数学（恒定乘积 + 0.3% 手续费）")
class AmmMathTest {

    private static final BigDecimal FEE = new BigDecimal("0.003");
    private static final BigDecimal R0 = new BigDecimal("10");
    private static final BigDecimal R1 = new BigDecimal("20000");

    @Nested
    @DisplayName("outputAmount 输出数量")
    class Output {

        @Test
        @DisplayName("标准用例：1000 USDC -> ETH，结果与按公式独立重算的值一致")
        void standardQuote() {
            BigDecimal reserveIn = R1;  // 20000 USDC
            BigDecimal reserveOut = R0; // 10 ETH
            BigDecimal out = AmmMath.outputAmount(new BigDecimal("1000"), reserveIn, reserveOut, FEE);
            // 独立按公式重算：(amountIn*(1-fee))*reserveOut / (reserveIn + amountIn*(1-fee))
            BigDecimal inFee = new BigDecimal("1000").multiply(BigDecimal.ONE.subtract(FEE))
                    .setScale(AmmMath.SCALE, AmmMath.ROUNDING);
            BigDecimal expected = inFee.multiply(reserveOut)
                    .divide(reserveIn.add(inFee), AmmMath.SCALE, AmmMath.ROUNDING);
            assertEquals(0, expected.compareTo(out));
            assertTrue(out.compareTo(new BigDecimal("0.47")) > 0, "1000U 在 1:2000 池应换到 0.47 ETH 以上，实际 " + out);
        }

        @Test
        @DisplayName("零输入给零输出")
        void zeroInput() {
            BigDecimal out = AmmMath.outputAmount(BigDecimal.ZERO, R1, R0, FEE);
            assertEquals(0, BigDecimal.ZERO.compareTo(out));
        }

        @Test
        @DisplayName("极小输入输出为 0（向下取整保护池）")
        void dustInput() {
            BigDecimal out = AmmMath.outputAmount(new BigDecimal("0.000000000000000001"), R1, R0, FEE);
            assertEquals(0, out.compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("边界：任何输入输出都严格小于输出侧储备，池不会被抽空")
        void outputAlwaysBelowReserve() {
            for (String in : new String[]{"1", "1000", "100000", "999999999"}) {
                BigDecimal out = AmmMath.outputAmount(new BigDecimal(in), R1, R0, FEE);
                assertTrue(out.compareTo(R0) < 0,
                        "输入 " + in + " 时输出 " + out + " 不应达到储备 " + R0);
            }
        }
    }

    @Nested
    @DisplayName("initialShares / addShares / redeemAmount 份额数学")
    class Shares {

        @Test
        @DisplayName("初始份额 = floor(sqrt(a*b))，LP 实得 = 总份额 - MINIMUM_LIQUIDITY")
        void initial() {
            BigDecimal[] got = AmmMath.initialShares(new BigDecimal("10"), new BigDecimal("20000"));
            // sqrt(200000) = 447.213595499957939...
            assertTrue(got[1].compareTo(new BigDecimal("447.213595499957939281")) == 0,
                    "总份额实际 " + got[1]);
            assertEquals(0, got[0].add(AmmMath.MINIMUM_LIQUIDITY).compareTo(got[1]),
                    "LP 份额 + 锁死份额 = 总份额");
        }

        @Test
        @DisplayName("按比例注入：份额与注入占比一致（取更吃亏一侧）")
        void addProportional() {
            BigDecimal total = new BigDecimal("447.213595499957939281");
            BigDecimal shares = AmmMath.addShares(new BigDecimal("5"), new BigDecimal("10000"),
                    new BigDecimal("10"), new BigDecimal("20000"), total);
            // 严格等比时两份相等且等于总份额一半，FLOOR 后不超过一半
            assertTrue(shares.compareTo(total.divide(new BigDecimal("2"), AmmMath.SCALE, AmmMath.ROUNDING)) <= 0);
            assertTrue(shares.compareTo(new BigDecimal("223.6")) > 0);
        }

        @Test
        @DisplayName("不成比例注入按吃亏侧算份额，不稀释既有 LP")
        void addImbalanced() {
            BigDecimal total = new BigDecimal("447.213595499957939281");
            // base 侧比例 100%，quote 侧只有 1%，应按 quote 侧（1%）发份额
            BigDecimal shares = AmmMath.addShares(new BigDecimal("10"), new BigDecimal("200"),
                    new BigDecimal("10"), new BigDecimal("20000"), total);
            assertTrue(shares.compareTo(new BigDecimal("4.48")) < 0,
                    "吃亏侧发份额应接近总份额 1%，实际 " + shares);
        }

        @Test
        @DisplayName("赎回数量 = floor(shares/total * reserve)，两边都不超过储备")
        void redeem() {
            BigDecimal total = new BigDecimal("447.213595499957939281");
            BigDecimal half = new BigDecimal("223.606797749978969640");
            BigDecimal baseOut = AmmMath.redeemAmount(half, R0, total);
            BigDecimal quoteOut = AmmMath.redeemAmount(half, R1, total);
            assertTrue(baseOut.compareTo(new BigDecimal("5")) <= 0);
            assertTrue(quoteOut.compareTo(new BigDecimal("10000")) <= 0);
            assertTrue(baseOut.compareTo(new BigDecimal("4.999")) > 0, "FLOOR 半份应几乎等于 5，实际 " + baseOut);
        }
    }
}
