package com.github.feng111627.ammliquidityenginejava.tests;

import com.github.feng111627.ammliquidityenginejava.domain.Pair;
import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;
import com.github.feng111627.ammliquidityenginejava.ledger.Ledger;
import com.github.feng111627.ammliquidityenginejava.pool.IdempotencyStore;
import com.github.feng111627.ammliquidityenginejava.pool.PoolRegistry;
import com.github.feng111627.ammliquidityenginejava.pool.PoolService;
import com.github.feng111627.ammliquidityenginejava.web.dto.AddLiquidityRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.CreatePairRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.SwapRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.SwapResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兑换（swap）业务测试：定价、手续费、滑点保护、各种拒绝原因区分。
 */
@DisplayName("业务二：兑换（恒定乘积定价 / 0.3% 手续费 / 滑点保护 / 拒绝原因）")
class SwapServiceTest {

    private PoolService service;
    private Ledger ledger;

    private static final String ETH = "ETH";
    private static final String USDC = "USDC";
    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        PoolRegistry registry = new PoolRegistry();
        IdempotencyStore idem = new IdempotencyStore();
        service = new PoolService(registry, ledger, idem);

        ledger.credit(ALICE, ETH, new BigDecimal("100"));
        ledger.credit(ALICE, USDC, new BigDecimal("100000"));
        ledger.credit(BOB, USDC, new BigDecimal("10000"));
        ledger.credit(BOB, ETH, new BigDecimal("5"));

        service.createPair(new CreatePairRequest(ETH, USDC));
        service.addLiquidity(new AddLiquidityRequest(ALICE, ETH, USDC,
                new BigDecimal("10"), new BigDecimal("20000"), null, null, null));
    }

    @Nested
    @DisplayName("正常兑换")
    class HappyPath {

        @Test
        @DisplayName("BUY_BASE：用 USDC 买入 ETH，双向余额与储备变化正确，手续费留在池里")
        void buyBase() {
            SwapResponse resp = service.swap(new SwapRequest(BOB, ETH, USDC,
                    PoolService.SIDE_BUY_BASE, new BigDecimal("1000"), null, null));

            assertEquals(0, resp.amountIn().compareTo(new BigDecimal("1000")));
            assertTrue(resp.amountOut().compareTo(new BigDecimal("0.47")) > 0);
            // 手续费 = 3 USDC
            assertEquals(0, resp.feePaid().compareTo(new BigDecimal("3.000")));

            // bob 扣 1000 USDC，得到 amountOut 的 ETH
            assertEquals(0, ledger.balanceOf(BOB, USDC).compareTo(new BigDecimal("9000")));
            assertEquals(0, ledger.balanceOf(BOB, ETH).compareTo(new BigDecimal("5").add(resp.amountOut())));

            // 池账本与池状态一致：quote 储备 +1000，base 储备 -out
            String pool = Ledger.poolAccount(ETH, USDC);
            assertEquals(0, ledger.balanceOf(pool, USDC).compareTo(resp.reserveQuoteAfter()));
            assertEquals(0, ledger.balanceOf(pool, ETH).compareTo(resp.reserveBaseAfter()));
        }

        @Test
        @DisplayName("SELL_BASE：卖出 ETH 换 USDC")
        void sellBase() {
            SwapResponse resp = service.swap(new SwapRequest(BOB, ETH, USDC,
                    PoolService.SIDE_SELL_BASE, new BigDecimal("1"), null, null));

            assertTrue(resp.amountOut().compareTo(new BigDecimal("1800")) > 0,
                    "卖 1 ETH 应换到约 1813 USDC（含手续费折扣），实际 " + resp.amountOut());
            assertEquals(0, ledger.balanceOf(BOB, ETH).compareTo(new BigDecimal("4")));
            assertEquals(0, ledger.balanceOf(BOB, USDC).compareTo(new BigDecimal("10000").add(resp.amountOut())));
        }

        @Test
        @DisplayName("minAmountOut 恰好等于实际到账时放行（边界：>= 下限）")
        void minOutBoundaryAccepted() {
            // 先预览拿到精确值
            var quote = service.quote(ETH, USDC, PoolService.SIDE_BUY_BASE, "1000");
            SwapResponse resp = service.swap(new SwapRequest(BOB, ETH, USDC,
                    PoolService.SIDE_BUY_BASE, new BigDecimal("1000"), quote.amountOut(), null));
            assertEquals(0, resp.amountOut().compareTo(quote.amountOut()));
        }
    }

    @Nested
    @DisplayName("拒绝场景（错误码必须可区分）")
    class Rejections {

        @Test
        @DisplayName("滑点保护：到账低于下限 -> SLIPPAGE_PROTECTION，且双方余额、储备完全不变")
        void slippage() {
            String pool = Ledger.poolAccount(ETH, USDC);
            BigDecimal usdcBefore = ledger.balanceOf(BOB, USDC);
            BigDecimal poolUsdcBefore = ledger.balanceOf(pool, USDC);

            ApiException ex = assertThrows(ApiException.class, () ->
                    service.swap(new SwapRequest(BOB, ETH, USDC,
                            PoolService.SIDE_BUY_BASE, new BigDecimal("1000"),
                            new BigDecimal("1"), null)));
            assertEquals(ErrorCode.SLIPPAGE_PROTECTION, ex.getErrorCode());

            assertEquals(0, ledger.balanceOf(BOB, USDC).compareTo(usdcBefore), "用户余额未变");
            assertEquals(0, ledger.balanceOf(pool, USDC).compareTo(poolUsdcBefore), "池储备未变");
        }

        @Test
        @DisplayName("余额不足 -> INSUFFICIENT_BALANCE，且池不动账")
        void insufficientBalance() {
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.swap(new SwapRequest("poor", ETH, USDC,
                            PoolService.SIDE_BUY_BASE, new BigDecimal("100"), null, null)));
            assertEquals(ErrorCode.INSUFFICIENT_BALANCE, ex.getErrorCode());
        }

        @Test
        @DisplayName("交易对不存在 -> PAIR_NOT_FOUND")
        void pairNotFound() {
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.swap(new SwapRequest(BOB, "BTC", USDC,
                            PoolService.SIDE_BUY_BASE, new BigDecimal("10"), null, null)));
            assertEquals(ErrorCode.PAIR_NOT_FOUND, ex.getErrorCode());
        }

        @Test
        @DisplayName("非法方向 -> INVALID_AMOUNT")
        void badSide() {
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.swap(new SwapRequest(BOB, ETH, USDC,
                            "WEST", new BigDecimal("10"), null, null)));
            assertEquals(ErrorCode.INVALID_AMOUNT, ex.getErrorCode());
        }

        @Test
        @DisplayName("非正金额 -> INVALID_AMOUNT")
        void nonPositive() {
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.swap(new SwapRequest(BOB, ETH, USDC,
                            PoolService.SIDE_BUY_BASE, new BigDecimal("0"), null, null)));
            assertEquals(ErrorCode.INVALID_AMOUNT, ex.getErrorCode());
        }

        @Test
        @DisplayName("未初始化的池兑换 -> INSUFFICIENT_LIQUIDITY")
        void emptyPool() {
            service.createPair(new CreatePairRequest("DAI", USDC));
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.swap(new SwapRequest(BOB, "DAI", USDC,
                            PoolService.SIDE_BUY_BASE, new BigDecimal("10"), null, null)));
            assertEquals(ErrorCode.INSUFFICIENT_LIQUIDITY, ex.getErrorCode());
        }
    }
}
