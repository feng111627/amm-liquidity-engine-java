package com.github.feng111627.ammliquidityenginejava.amm;

import com.github.feng111627.ammliquidityenginejava.amm.model.AddLiquidityResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.RemoveLiquidityResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.SwapResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.TradingPair;
import com.github.feng111627.ammliquidityenginejava.amm.service.AmmExchangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 幂等路径：同一幂等标识重试返回首次结果，不重复扣账 */
@ExtendWith(BusinessCaseLogger.class)
class IdempotencyTest {

    private AmmExchangeService service;

    @BeforeEach
    void setUp() {
        service = new AmmExchangeService();
        service.createPair("ETH-USDC", "ETH", "USDC");
        service.addLiquidity("ETH-USDC", "lp0",
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(1_000_000_000),
                "seed");
    }

    @Test
    @DisplayName("幂等-兑换重试：同一 idempotencyKey 重试返回相同结果，储备只变动一次")
    void swapRetryReturnsSameResult() {
        SwapResult first = service.swap("ETH-USDC", "ETH",
                BigInteger.valueOf(10_000), BigInteger.ZERO, "swap-key-1");
        BigInteger reserveAAfterFirst = first.reserveAAfter();
        BigInteger reserveBAfterFirst = first.reserveBAfter();

        SwapResult retry = service.swap("ETH-USDC", "ETH",
                BigInteger.valueOf(10_000), BigInteger.ZERO, "swap-key-1");
        assertEquals(first, retry, "重试必须返回首次的成交结果");

        TradingPair pair = service.getPair("ETH-USDC");
        assertEquals(reserveAAfterFirst, pair.getReserveA(), "重试不得重复扣账");
        assertEquals(reserveBAfterFirst, pair.getReserveB(), "重试不得重复出账");
    }

    @Test
    @DisplayName("幂等-注入重试：份额只铸造一次")
    void addLiquidityRetryMintsOnce() {
        AddLiquidityResult first = service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(100_000), BigInteger.valueOf(100_000_000),
                "add-key-1");
        AddLiquidityResult retry = service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(100_000), BigInteger.valueOf(100_000_000),
                "add-key-1");
        assertEquals(first, retry);
        assertEquals(first.totalSharesAfter(),
                service.getPair("ETH-USDC").getTotalShares(), "份额未被重复铸造");
    }

    @Test
    @DisplayName("幂等-赎回重试：份额只核销一次，不会重复出账")
    void removeLiquidityRetryBurnsOnce() {
        service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(100_000), BigInteger.valueOf(100_000_000),
                "add-key-1");
        BigInteger shares = service.getPair("ETH-USDC").getSharesOf().get("alice");

        RemoveLiquidityResult first = service.removeLiquidity("ETH-USDC", "alice",
                shares, "rm-key-1");
        RemoveLiquidityResult retry = service.removeLiquidity("ETH-USDC", "alice",
                shares, "rm-key-1");
        assertEquals(first, retry);
        // 若重试被当成新请求，此时份额已不足会抛异常；能返回相同结果说明走了幂等
        assertEquals(first.totalSharesAfter(),
                service.getPair("ETH-USDC").getTotalShares());
    }

    @Test
    @DisplayName("幂等-并发重试：多线程用同一标识同时提交，全部得到同一结果且只执行一次")
    void concurrentRetryExecutesOnce() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SwapResult>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                return service.swap("ETH-USDC", "ETH",
                        BigInteger.valueOf(10_000), BigInteger.ZERO, "shared-key");
            }));
        }
        go.countDown();

        SwapResult first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<SwapResult> f : futures) {
            assertEquals(first, f.get(30, TimeUnit.SECONDS),
                    "所有并发重试必须得到同一结果");
        }
        pool.shutdown();

        TradingPair pair = service.getPair("ETH-USDC");
        assertEquals(first.reserveAAfter(), pair.getReserveA(),
                "并发重试下储备只被扣减一次");
        assertEquals(first.reserveBAfter(), pair.getReserveB());
    }

    @Test
    @DisplayName("幂等-不同标识：不同 idempotencyKey 视为独立请求，各自正常执行")
    void differentKeysExecuteIndependently() {
        SwapResult r1 = service.swap("ETH-USDC", "ETH",
                BigInteger.valueOf(10_000), BigInteger.ZERO, "key-a");
        SwapResult r2 = service.swap("ETH-USDC", "ETH",
                BigInteger.valueOf(10_000), BigInteger.ZERO, "key-b");
        assertNotEquals(r1.reserveAAfter(), r2.reserveAAfter(),
                "两笔独立兑换应各自改变储备");
        assertEquals(r2.reserveAAfter(), service.getPair("ETH-USDC").getReserveA());
    }
}
