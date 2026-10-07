package com.github.feng111627.ammliquidityenginejava.amm;

import com.github.feng111627.ammliquidityenginejava.amm.model.RemoveLiquidityResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.SwapResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.TradingPair;
import com.github.feng111627.ammliquidityenginejava.amm.service.AmmExchangeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 并发路径：同一交易对上并发兑换/注入/赎回，储备与份额必须始终对得上 */
@ExtendWith(BusinessCaseLogger.class)
class ConcurrencyTest {

    @Test
    @DisplayName("并发-兑换：多线程同时兑换，储备不出现负值且账目可核对")
    void concurrentSwapsKeepReservesConsistent() throws Exception {
        AmmExchangeService service = new AmmExchangeService();
        service.createPair("ETH-USDC", "ETH", "USDC");
        service.addLiquidity("ETH-USDC", "lp0",
                BigInteger.valueOf(10_000_000), BigInteger.valueOf(10_000_000_000L),
                "seed");

        int threads = 16, swapsPerThread = 50;
        BigInteger amountIn = BigInteger.valueOf(1_000);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue();
        AtomicInteger succeeded = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                for (int i = 0; i < swapsPerThread; i++) {
                    try {
                        // 双向交替兑换，避免单边把池子推得太偏
                        String tokenIn = (i % 2 == 0) ? "ETH" : "USDC";
                        BigInteger in = (i % 2 == 0) ? amountIn
                                : amountIn.multiply(BigInteger.valueOf(900));
                        service.swap("ETH-USDC", tokenIn, in,
                                BigInteger.ZERO, "swap-" + tid + "-" + i);
                        succeeded.incrementAndGet();
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }
                return null;
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(errors.isEmpty(), "并发兑换不应抛异常: " + errors.peek());
        assertEquals(threads * swapsPerThread, succeeded.get());

        TradingPair pair = service.getPair("ETH-USDC");
        assertTrue(pair.getReserveA().signum() > 0, "储备 A 不为负");
        assertTrue(pair.getReserveB().signum() > 0, "储备 B 不为负");
        // 手续费沉淀：k 只增不减
        BigInteger kAfter = pair.getReserveA().multiply(pair.getReserveB());
        assertTrue(kAfter.compareTo(
                BigInteger.valueOf(10_000_000).multiply(BigInteger.valueOf(10_000_000_000L))) > 0);
    }

    @Test
    @DisplayName("并发-赎回：多线程赎回同一 LP 的份额，总赎回量不超过持有量")
    void concurrentRemovesNeverOverdraw() throws Exception {
        AmmExchangeService service = new AmmExchangeService();
        service.createPair("ETH-USDC", "ETH", "USDC");
        BigInteger minted = service.addLiquidity("ETH-USDC", "alice",
                BigInteger.valueOf(1_000_000_000), BigInteger.valueOf(1_000_000_000),
                "seed").sharesMinted();

        int threads = 32;
        BigInteger chunk = minted.divide(BigInteger.valueOf(threads));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<BigInteger>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            tasks.add(() -> {
                go.await();
                BigInteger redeemed = BigInteger.ZERO;
                // 每个线程尝试赎回 chunk+1 份：最后 +1 那笔应因份额不足被拒
                try {
                    RemoveLiquidityResult r = service.removeLiquidity(
                            "ETH-USDC", "alice", chunk.add(BigInteger.ONE),
                            "rm-" + tid);
                    redeemed = r.sharesBurned();
                } catch (IllegalArgumentException expected) {
                    // 份额不足，正常拒绝
                }
                return redeemed;
            });
        }
        List<Future<BigInteger>> futures = new ArrayList<>();
        for (Callable<BigInteger> c : tasks) futures.add(pool.submit(c));
        go.countDown();

        BigInteger totalRedeemed = BigInteger.ZERO;
        for (Future<BigInteger> f : futures) {
            totalRedeemed = totalRedeemed.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        TradingPair pair = service.getPair("ETH-USDC");
        assertTrue(totalRedeemed.compareTo(minted) <= 0,
                "总赎回份额不能超过铸造量");
        assertEquals(minted.subtract(totalRedeemed), pair.getTotalShares(),
                "剩余份额 = 铸造量 - 已赎回量");
        assertEquals(pair.getTotalShares(),
                pair.getSharesOf().getOrDefault("alice", BigInteger.ZERO),
                "LP 账本与总份额一致");
        assertTrue(pair.getReserveA().signum() >= 0 && pair.getReserveB().signum() >= 0);
    }

    @Test
    @DisplayName("并发-混合：兑换与注入/赎回同时进行，最终账目守恒")
    void concurrentMixedOperationsConserve() throws Exception {
        AmmExchangeService service = new AmmExchangeService();
        service.createPair("ETH-USDC", "ETH", "USDC");
        service.addLiquidity("ETH-USDC", "lp0",
                BigInteger.valueOf(10_000_000), BigInteger.valueOf(10_000_000_000L),
                "seed");

        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < 4; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 30; i++) {
                    try {
                        service.swap("ETH-USDC", "ETH",
                                BigInteger.valueOf(500), BigInteger.ZERO,
                                "mix-swap-" + tid + "-" + i);
                    } catch (Throwable e) { errors.add(e); }
                }
                return null;
            }));
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    service.addLiquidity("ETH-USDC", "lp" + (tid + 1),
                            BigInteger.valueOf(100_000),
                            BigInteger.valueOf(100_000_000), "mix-add-" + tid);
                } catch (Throwable e) { errors.add(e); }
                return null;
            }));
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    service.removeLiquidity("ETH-USDC", "lp0",
                            BigInteger.valueOf(1_000), "mix-rm-" + tid);
                } catch (Throwable e) { errors.add(e); }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(errors.isEmpty(), "混合并发不应抛异常: " + errors.peek());

        TradingPair pair = service.getPair("ETH-USDC");
        // 份额账本守恒：各 LP 余额之和 == 总份额
        BigInteger sumShares = pair.getSharesOf().values().stream()
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(pair.getTotalShares(), sumShares, "份额账本与总份额一致");
        assertTrue(pair.getReserveA().signum() > 0 && pair.getReserveB().signum() > 0);
    }
}
