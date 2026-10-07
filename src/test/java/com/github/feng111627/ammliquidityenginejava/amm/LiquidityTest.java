package com.github.feng111627.ammliquidityenginejava.amm;

import com.github.feng111627.ammliquidityenginejava.amm.model.AddLiquidityResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.RemoveLiquidityResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.TradingPair;
import com.github.feng111627.ammliquidityenginejava.amm.service.AmmExchangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 流动性路径：份额铸造、等比例赎回、手续费沉淀进份额价值、份额不超发不少扣 */
@ExtendWith(BusinessCaseLogger.class)
class LiquidityTest {

    private AmmExchangeService service;

    @BeforeEach
    void setUp() {
        service = new AmmExchangeService();
        service.createPair("ETH-USDC", "ETH", "USDC");
    }

    @Test
    @DisplayName("流动性-首次注入：份额 = sqrt(amountA * amountB)")
    void firstAddMintsSqrtShares() {
        AddLiquidityResult r = service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(4_000_000), BigInteger.valueOf(9_000_000), "add-1");
        // sqrt(4e6 * 9e6) = 6e6
        assertEquals(BigInteger.valueOf(6_000_000), r.sharesMinted());
        assertEquals(BigInteger.valueOf(6_000_000), r.totalSharesAfter());
    }

    @Test
    @DisplayName("流动性-追加注入：份额按注入时池内实际比例计算，取两侧较小值")
    void subsequentAddMintsProportionalShares() {
        service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(4_000_000), "add-1");
        // 池子 1e6 / 4e6，总份额 2e6；注入 500k / 4e6
        // sharesA = 500k*2e6/1e6 = 1e6；sharesB = 4e6*2e6/4e6 = 2e6；取小 = 1e6
        AddLiquidityResult r = service.addLiquidity("ETH-USDC", "bob",
                BigInteger.valueOf(500_000), BigInteger.valueOf(4_000_000), "add-2");
        assertEquals(BigInteger.valueOf(1_000_000), r.sharesMinted());
        assertEquals(BigInteger.valueOf(3_000_000), r.totalSharesAfter());
    }

    @Test
    @DisplayName("流动性-赎回：按份额占比等比例取回两侧储备，份额同步核销")
    void removeReturnsProportionalReserves() {
        service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(4_000_000), "add-1");
        // 总份额 2e6，赎回一半
        RemoveLiquidityResult r = service.removeLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(1_000_000), "rm-1");
        assertEquals(BigInteger.valueOf(500_000), r.amountAOut());
        assertEquals(BigInteger.valueOf(2_000_000), r.amountBOut());
        assertEquals(BigInteger.valueOf(1_000_000), r.totalSharesAfter());

        TradingPair pair = service.getPair("ETH-USDC");
        assertEquals(BigInteger.valueOf(1_000_000),
                pair.getSharesOf().get("alice"), "剩余份额记录一致");
        // 储备与份额对应关系未被破坏：alice 赎回剩余全部后池子清零
        service.removeLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(1_000_000), "rm-2");
        assertEquals(BigInteger.ZERO, pair.getTotalShares());
        assertEquals(BigInteger.ZERO, pair.getReserveA());
        assertEquals(BigInteger.ZERO, pair.getReserveB());
    }

    @Test
    @DisplayName("流动性-手续费沉淀：兑换产生的手续费留在池内并体现在份额价值上")
    void feesAccrueToShareValue() {
        service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(1_000_000_000),
                "add-1");
        TradingPair pair = service.getPair("ETH-USDC");
        BigInteger totalShares = pair.getTotalShares();

        // 多笔兑换让手续费沉淀
        for (int i = 0; i < 10; i++) {
            service.swap("ETH-USDC", "ETH",
                    BigInteger.valueOf(10_000), BigInteger.ZERO, "swap-" + i);
        }

        // 份额总量不变，但每份对应的储备变多了
        assertEquals(totalShares, pair.getTotalShares());
        RemoveLiquidityResult r = service.removeLiquidity("ETH-USDC", "alice",
                totalShares, "rm-all");
        assertTrue(r.amountAOut().compareTo(BigInteger.valueOf(1_000_000)) > 0,
                "赎回的 ETH 应多于初始注入（手续费沉淀）");
    }

    @Test
    @DisplayName("流动性-赎回保护：超出持有份额的赎回被拒绝，不会凭空多发")
    void removeBeyondBalanceRejected() {
        service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(1_000_000), "add-1");
        BigInteger balance = service.getPair("ETH-USDC").getTotalShares();
        assertThrows(IllegalArgumentException.class,
                () -> service.removeLiquidity("ETH-USDC", "alice",
                        balance.add(BigInteger.ONE), "rm-over"));
        assertThrows(IllegalArgumentException.class,
                () -> service.removeLiquidity("ETH-USDC", "mallory",
                        BigInteger.ONE, "rm-stranger"));
    }

    @Test
    @DisplayName("流动性-边界：零数量注入/赎回、粉尘注入无法铸份额均被拒绝")
    void rejectsZeroAndDustAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addLiquidity("ETH-USDC", "alice",
                        BigInteger.ZERO, BigInteger.TEN, "add-zero"));
        // 失衡池子中相对某一侧过小的注入铸不出份额
        service.createPair("X-Y", "X", "Y");
        service.addLiquidity("X-Y", "alice",
                BigInteger.valueOf(1_000_000), new BigInteger("1000000000000"),
                "add-imbalance");
        assertThrows(IllegalArgumentException.class,
                () -> service.addLiquidity("X-Y", "bob",
                        BigInteger.ONE, BigInteger.ONE, "add-dust"));
        assertThrows(IllegalArgumentException.class,
                () -> service.removeLiquidity("ETH-USDC", "alice",
                        BigInteger.ZERO, "rm-zero"));
    }
}
