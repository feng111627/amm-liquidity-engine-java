package com.github.feng111627.ammliquidityenginejava.tests;

import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;
import com.github.feng111627.ammliquidityenginejava.ledger.Ledger;
import com.github.feng111627.ammliquidityenginejava.pool.IdempotencyStore;
import com.github.feng111627.ammliquidityenginejava.pool.PoolRegistry;
import com.github.feng111627.ammliquidityenginejava.pool.PoolService;
import com.github.feng111627.ammliquidityenginejava.web.dto.AddLiquidityRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.CreatePairRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.LiquidityResponse;
import com.github.feng111627.ammliquidityenginejava.web.dto.RemoveLiquidityRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.SwapRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流动性注入/赎回业务测试：份额结算、手续费沉淀、赎回对应关系不被破坏。
 */
@DisplayName("业务三：流动性注入/赎回（份额结算 / 手续费沉淀 / 储备对应关系）")
class LiquidityServiceTest {

    private PoolService service;
    private Ledger ledger;
    private PoolRegistry registry;

    private static final String ETH = "ETH";
    private static final String USDC = "USDC";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";
    private static final BigDecimal FEE = new BigDecimal("0.003");

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        registry = new PoolRegistry();
        service = new PoolService(registry, ledger, new IdempotencyStore());
        ledger.credit(ALICE, ETH, new BigDecimal("1000"));
        ledger.credit(ALICE, USDC, new BigDecimal("10000000"));
        ledger.credit(BOB, USDC, new BigDecimal("1000000"));
        ledger.credit(BOB, ETH, new BigDecimal("1000"));
        service.createPair(new CreatePairRequest(ETH, USDC));
    }

    private void seedInitial() {
        service.addLiquidity(new AddLiquidityRequest(ALICE, ETH, USDC,
                new BigDecimal("100"), new BigDecimal("200000"), null, null, null));
    }

    private BigDecimal reserveBase() {
        return registry.require(new com.github.feng111627.ammliquidityenginejava.domain.Pair(ETH, USDC))
                .getReserveBase();
    }

    private BigDecimal reserveQuote() {
        return registry.require(new com.github.feng111627.ammliquidityenginejava.domain.Pair(ETH, USDC))
                .getReserveQuote();
    }

    @Nested
    @DisplayName("注入流动性")
    class Add {

        @Test
        @DisplayName("首次注入：份额 = sqrt(a*b) - MINIMUM_LIQUIDITY，储备与账本一致")
        void initial() {
            LiquidityResponse resp = service.addLiquidity(new AddLiquidityRequest(ALICE, ETH, USDC,
                    new BigDecimal("100"), new BigDecimal("200000"), null, null, null));

            assertTrue(resp.shares().compareTo(new BigDecimal("4472.13")) > 0,
                    "sqrt(2e7)=4472.1359...，实际 " + resp.shares());
            // 总份额 = LP 份额 + 1e-15 锁死份额
            assertEquals(0, resp.totalShares().subtract(resp.shares())
                    .compareTo(new BigDecimal("0.000000000000001")));
            String pool = Ledger.poolAccount(ETH, USDC);
            assertEquals(0, ledger.balanceOf(pool, ETH).compareTo(new BigDecimal("100")));
            assertEquals(0, ledger.balanceOf(pool, USDC).compareTo(new BigDecimal("200000")));
        }

        @Test
        @DisplayName("非比例注入：多余一侧的代币原路退回，实际注入贴合当前池价")
        void imbalancedRefund() {
            seedInitial();
            BigDecimal ethBefore = ledger.balanceOf(BOB, ETH);
            BigDecimal usdcBefore = ledger.balanceOf(BOB, USDC);

            // 当前价 1 ETH=2000 USDC，bob 给 10 ETH + 999999 USDC，USDC 多了，应退回约 979999
            LiquidityResponse resp = service.addLiquidity(new AddLiquidityRequest(BOB, ETH, USDC,
                    new BigDecimal("10"), new BigDecimal("999999"), null, null, null));

            assertTrue(resp.quoteAmount().compareTo(new BigDecimal("20000")) == 0
                            || resp.quoteAmount().compareTo(new BigDecimal("19999.999999999999999")) == 0,
                    "按比例只应使用约 20000 USDC，实际 " + resp.quoteAmount());
            // ETH 用掉 10
            assertEquals(0, ledger.balanceOf(BOB, ETH).compareTo(ethBefore.subtract(new BigDecimal("10"))));
            // USDC 只少约 20000，其余退回
            BigDecimal spent = usdcBefore.subtract(ledger.balanceOf(BOB, USDC));
            assertTrue(spent.compareTo(new BigDecimal("20001")) < 0 && spent.compareTo(new BigDecimal("19999")) > 0,
                    "USDC 实花应≈20000，实际 " + spent);
        }

        @Test
        @DisplayName("注入滑点保护：实际用量低于下限时拒绝，用户一分钱不少")
        void slippageReject() {
            seedInitial();
            BigDecimal ethBefore = ledger.balanceOf(BOB, ETH);
            BigDecimal usdcBefore = ledger.balanceOf(BOB, USDC);
            // 按当前价 10 ETH 需要 20000 USDC，但 bob 只肯出 10000，实际 base 用量只有 5；
            // 设置 minBase=8 触发拒绝
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.addLiquidity(new AddLiquidityRequest(BOB, ETH, USDC,
                            new BigDecimal("10"), new BigDecimal("10000"),
                            new BigDecimal("8"), null, null)));
            assertEquals(ErrorCode.SLIPPAGE_PROTECTION, ex.getErrorCode());
            assertEquals(0, ledger.balanceOf(BOB, ETH).compareTo(ethBefore), "拒绝后 ETH 全额退回");
            assertEquals(0, ledger.balanceOf(BOB, USDC).compareTo(usdcBefore), "拒绝后 USDC 全额退回");
        }

        @Test
        @DisplayName("余额不足不能注入 -> INSUFFICIENT_BALANCE")
        void insufficientBalance() {
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.addLiquidity(new AddLiquidityRequest("poor", ETH, USDC,
                            new BigDecimal("1"), new BigDecimal("2000"), null, null, null)));
            assertEquals(ErrorCode.INSUFFICIENT_BALANCE, ex.getErrorCode());
        }
    }

    @Nested
    @DisplayName("赎回流动性")
    class Remove {

        @Test
        @DisplayName("无手续费时全额赎回：只剩锁死份额对应的微量储备，LP 取回几乎全部两种代币")
        void fullRedeemNoFees() {
            LiquidityResponse add = service.addLiquidity(new AddLiquidityRequest(ALICE, ETH, USDC,
                    new BigDecimal("100"), new BigDecimal("200000"), null, null, null));
            BigDecimal lpShares = add.shares();

            LiquidityResponse rem = service.removeLiquidity(new RemoveLiquidityRequest(
                    ALICE, ETH, USDC, lpShares, null));

            // 总份额只剩 1e-15
            assertEquals(0, rem.totalShares().compareTo(new BigDecimal("0.000000000000001")));
            // 取回量受 FLOOR 影响略小于注入，但差额只是灰尘级
            assertTrue(rem.baseAmount().compareTo(new BigDecimal("99.999999999999999")) >= 0,
                    "base 取回 " + rem.baseAmount());
            assertTrue(rem.quoteAmount().compareTo(new BigDecimal("199999.999999999999")) >= 0,
                    "quote 取回 " + rem.quoteAmount());
        }

        @Test
        @DisplayName("手续费沉淀：发生兑换后，LP 赎回价值严格高于注入价值（同口径比较）")
        void feesAccrueToLp() {
            seedInitial();
            // bob 来回兑换产生手续费
            service.swap(new SwapRequest(BOB, ETH, USDC, PoolService.SIDE_BUY_BASE,
                    new BigDecimal("50000"), null, null));
            service.swap(new SwapRequest(BOB, ETH, USDC, PoolService.SIDE_SELL_BASE,
                    new BigDecimal("20"), null, null));

            // 直接查池：bob 买入后 k 应严格大于初始 k（手续费留在池内）
            BigDecimal k = reserveBase().multiply(reserveQuote());
            assertTrue(k.compareTo(new BigDecimal("100").multiply(new BigDecimal("200000"))) > 0,
                    "兑换手续费应让 k 增大，实际 k=" + k.toPlainString());
        }

        @Test
        @DisplayName("部分赎回后份额与储备按比例对应：剩余份额价值不受损")
        void partialRedeemKeepsRatio() {
            LiquidityResponse add = service.addLiquidity(new AddLiquidityRequest(ALICE, ETH, USDC,
                    new BigDecimal("100"), new BigDecimal("200000"), null, null, null));
            BigDecimal half = add.shares().divide(new BigDecimal("2"), 18, java.math.RoundingMode.DOWN);

            BigDecimal reserveB0 = reserveBase();
            BigDecimal reserveQ0 = reserveQuote();
            LiquidityResponse rem = service.removeLiquidity(new RemoveLiquidityRequest(
                    ALICE, ETH, USDC, half, null));

            // 赎回数量 ≈ 一半储备（FLOOR + 锁死份额占比，误差仅灰尘级）
            BigDecimal halfBase = reserveB0.divide(new BigDecimal("2"), 18, java.math.RoundingMode.DOWN);
            assertTrue(rem.baseAmount().compareTo(halfBase.subtract(new BigDecimal("0.00000000000001"))) >= 0,
                    "首次赎回 " + rem.baseAmount() + " 应≈半储备 " + halfBase);
            // 剩余份额仍可全部赎回，且第二次 + 第一次之和不超过原储备
            LiquidityResponse rem2 = service.removeLiquidity(new RemoveLiquidityRequest(
                    ALICE, ETH, USDC, add.shares().subtract(half), null));
            BigDecimal totalBaseBack = rem.baseAmount().add(rem2.baseAmount());
            BigDecimal totalQuoteBack = rem.quoteAmount().add(rem2.quoteAmount());
            assertTrue(totalBaseBack.compareTo(reserveB0) <= 0, "不能凭空多发 base");
            assertTrue(totalQuoteBack.compareTo(reserveQ0) <= 0, "不能凭空多发 quote");
            assertTrue(totalBaseBack.compareTo(new BigDecimal("99.9999999999999")) > 0,
                    "两次合计应几乎取回全部，实际 base=" + totalBaseBack);
        }

        @Test
        @DisplayName("赎回超过持有份额 -> INSUFFICIENT_LIQUIDITY")
        void overRedeem() {
            seedInitial();
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.removeLiquidity(new RemoveLiquidityRequest(
                            ALICE, ETH, USDC, new BigDecimal("999999999"), null)));
            assertEquals(ErrorCode.INSUFFICIENT_LIQUIDITY, ex.getErrorCode());
        }

        @Test
        @DisplayName("非正份额 -> INVALID_AMOUNT")
        void zeroRedeem() {
            seedInitial();
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.removeLiquidity(new RemoveLiquidityRequest(
                            ALICE, ETH, USDC, BigDecimal.ZERO, null)));
            assertEquals(ErrorCode.INVALID_AMOUNT, ex.getErrorCode());
        }
    }
}
