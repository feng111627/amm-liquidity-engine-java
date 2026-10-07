package com.github.feng111627.ammliquidityenginejava.amm;

import com.github.feng111627.ammliquidityenginejava.amm.exception.InsufficientLiquidityException;
import com.github.feng111627.ammliquidityenginejava.amm.exception.PairNotFoundException;
import com.github.feng111627.ammliquidityenginejava.amm.exception.SlippageExceededException;
import com.github.feng111627.ammliquidityenginejava.amm.model.SwapResult;
import com.github.feng111627.ammliquidityenginejava.amm.service.AmmExchangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 兑换路径：定价公式、手续费、滑点保护、储备不足、参数校验 */
@ExtendWith(BusinessCaseLogger.class)
class SwapTest {

    private AmmExchangeService service;

    @BeforeEach
    void setUp() {
        service = new AmmExchangeService();
        service.createPair("ETH-USDC", "ETH", "USDC");
        // 1 ETH = 1000 USDC 的初始池
        service.addLiquidity("ETH-USDC", "lp0",
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(1_000_000_000),
                "seed-1");
    }

    @Test
    @DisplayName("兑换-恒定乘积定价：输出符合 x*y=k 公式且收取 0.3% 手续费")
    void swapFollowsConstantProductWithFee() {
        BigInteger amountIn = BigInteger.valueOf(10_000);
        SwapResult r = service.swap("ETH-USDC", "ETH", amountIn,
                BigInteger.ZERO, "swap-1");

        // amountOut = (in*9970*reserveOut) / (reserveIn*10000 + in*9970)
        BigInteger effective = amountIn.multiply(BigInteger.valueOf(9970));
        BigInteger expected = effective.multiply(BigInteger.valueOf(1_000_000_000))
                .divide(BigInteger.valueOf(1_000_000L * 10_000).add(effective));
        assertEquals(expected, r.amountOut());
        assertEquals(amountIn.multiply(BigInteger.valueOf(30))
                .divide(BigInteger.valueOf(10_000)), r.feeAmount());

        // 兑换后 k 不减（手续费沉淀在池内，k 实际应增大）
        BigInteger kBefore = BigInteger.valueOf(1_000_000L).multiply(BigInteger.valueOf(1_000_000_000L));
        BigInteger kAfter = r.reserveAAfter().multiply(r.reserveBAfter());
        assertTrue(kAfter.compareTo(kBefore) > 0, "手续费沉淀应使 k 增大");
    }

    @Test
    @DisplayName("兑换-滑点保护：到账低于最少到账数量时拒绝且报滑点原因")
    void swapRejectedWhenBelowMinOut() {
        BigInteger amountIn = BigInteger.valueOf(10_000);
        BigInteger realistic = service.swap("ETH-USDC", "ETH", amountIn,
                BigInteger.ZERO, "probe").amountOut();

        SlippageExceededException ex = assertThrows(SlippageExceededException.class,
                () -> service.swap("ETH-USDC", "ETH", amountIn,
                        realistic.add(BigInteger.ONE), "swap-slip"));
        assertTrue(ex.getMessage().contains("滑点"));
    }

    @Test
    @DisplayName("兑换-储备不足：池子为空或兑出需求超过储备时拒绝且原因区别于滑点")
    void swapRejectedWhenLiquidityInsufficient() {
        // 空池
        service.createPair("BTC-USDC", "BTC", "USDC");
        assertThrows(InsufficientLiquidityException.class,
                () -> service.swap("BTC-USDC", "BTC",
                        BigInteger.ONE, BigInteger.ZERO, "swap-empty"));

        // 极端失衡的池子：可兑出数量向下取整为 0，视为储备不足
        service.createPair("X-Y", "X", "Y");
        service.addLiquidity("X-Y", "lp1",
                new BigInteger("1000000000000"), BigInteger.TEN, "seed-2");
        assertThrows(InsufficientLiquidityException.class,
                () -> service.swap("X-Y", "X",
                        BigInteger.ONE, BigInteger.ZERO, "swap-dust"));

        // 巨额输入不会透支储备：到账渐近逼近但永远小于储备
        SwapResult huge = service.swap("ETH-USDC", "ETH",
                new BigInteger("999999999999999999999999"), BigInteger.ZERO,
                "swap-huge");
        assertTrue(huge.amountOut().compareTo(BigInteger.valueOf(1_000_000_000)) < 0);
        assertTrue(huge.reserveBAfter().signum() > 0, "储备不会被兑空");
    }

    @Test
    @DisplayName("兑换-参数校验：未知交易对、非法代币、非正数量均被拒绝")
    void swapRejectsInvalidParams() {
        assertThrows(PairNotFoundException.class,
                () -> service.swap("NOPE", "ETH",
                        BigInteger.ONE, BigInteger.ZERO, "k1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.swap("ETH-USDC", "DOGE",
                        BigInteger.ONE, BigInteger.ZERO, "k2"));
        assertThrows(IllegalArgumentException.class,
                () -> service.swap("ETH-USDC", "ETH",
                        BigInteger.ZERO, BigInteger.ZERO, "k3"));
    }

    @Test
    @DisplayName("兑换-反向兑换：USDC 换 ETH 方向同样按公式成交")
    void swapReverseDirection() {
        SwapResult r = service.swap("ETH-USDC", "USDC",
                BigInteger.valueOf(1_000_000), BigInteger.ZERO, "swap-rev");
        assertEquals("USDC", r.tokenIn());
        assertEquals("ETH", r.tokenOut());
        assertTrue(r.amountOut().compareTo(BigInteger.ZERO) > 0);
        assertTrue(r.amountOut().compareTo(BigInteger.valueOf(1_000)) < 0,
                "1000 USDC 应换到不足 1 个 ETH 单位");
    }
}
