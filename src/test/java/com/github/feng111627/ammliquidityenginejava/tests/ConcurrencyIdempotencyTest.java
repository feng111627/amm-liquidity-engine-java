package com.github.feng111627.ammliquidityenginejava.tests;

import com.github.feng111627.ammliquidityenginejava.domain.Pair;
import com.github.feng111627.ammliquidityenginejava.domain.Pool;
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
import com.github.feng111627.ammliquidityenginejava.web.dto.SwapResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发与幂等专项测试。
 */
@DisplayName("业务四：并发安全与幂等重试")
class ConcurrencyIdempotencyTest {

    private static final String ETH = "ETH";
    private static final String USDC = "USDC";
    private static final Pair PAIR = new Pair(ETH, USDC);

    private Ledger ledger;
    private PoolRegistry registry;
    private PoolService service;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        registry = new PoolRegistry();
        service = new PoolService(registry, ledger, new IdempotencyStore());
        service.createPair(new CreatePairRequest(ETH, USDC));

        // 初始流动性：10000 ETH / 20,000,000 USDC（大池子，方便并发压测）
        ledger.credit("lp", ETH, new BigDecimal("20000"));
        ledger.credit("lp", USDC, new BigDecimal("40000000"));
        service.addLiquidity(new AddLiquidityRequest("lp", ETH, USDC,
                new BigDecimal("10000"), new BigDecimal("20000000"), null, null, null));
    }

    private Pool pool() {
        return registry.require(PAIR);
    }

    @Nested
    @DisplayName("并发兑换")
    class ConcurrentSwaps {

        @Test
        @DisplayName("200 个不同用户并发买入：无异常、无超发，储备恒等于账本，且池始终满足 k 不减小")
        void parallelBuyersNoOverdraw() throws Exception {
            int n = 200;
            ExecutorService poolExec = Executors.newFixedThreadPool(20);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            AtomicInteger failures = new AtomicInteger();
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

            BigDecimal kBefore = pool().getReserveBase().multiply(pool().getReserveQuote());

            for (int i = 0; i < n; i++) {
                String user = "buyer-" + i;
                ledger.credit(user, USDC, new BigDecimal("100"));
                poolExec.submit(() -> {
                    try {
                        start.await();
                        service.swap(new SwapRequest(user, ETH, USDC,
                                PoolService.SIDE_BUY_BASE, new BigDecimal("100"), null, null));
                    } catch (Throwable t) {
                        failures.incrementAndGet();
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务应在超时前全部结束");
            poolExec.shutdown();

            assertEquals(0, failures.get(), "不应有失败：" + errors.stream().findFirst().map(Throwable::getMessage).orElse(""));

            // 1) 每个买入者的 ETH 都到账且为正数
            for (int i = 0; i < n; i++) {
                assertTrue(ledger.balanceOf("buyer-" + i, ETH).signum() > 0,
                        "buyer-" + i + " 的 ETH 应已到账");
                assertEquals(0, ledger.balanceOf("buyer-" + i, USDC).compareTo(BigDecimal.ZERO),
                        "每个买家恰好花掉 100 USDC");
            }
            // 2) 池储备 == 池账户账本
            String poolAccount = Ledger.poolAccount(ETH, USDC);
            assertEquals(0, ledger.balanceOf(poolAccount, ETH).compareTo(pool().getReserveBase()));
            assertEquals(0, ledger.balanceOf(poolAccount, USDC).compareTo(pool().getReserveQuote()));
            // 3) k 只增不减（手续费沉淀）
            BigDecimal kAfter = pool().getReserveBase().multiply(pool().getReserveQuote());
            assertTrue(kAfter.compareTo(kBefore) > 0, "并发兑换后 k 应增大：before=" + kBefore + " after=" + kAfter);
        }

        @Test
        @DisplayName("同一余额不足的用户并发发起多笔兑换：要么成功一笔，要么全部失败，绝不能成功两笔（不超支）")
        void sameUserConcurrentNoDoubleSpend() throws Exception {
            int n = 32;
            ledger.credit("broke", USDC, new BigDecimal("150"));
            ExecutorService poolExec = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger insufficient = new AtomicInteger();

            for (int i = 0; i < n; i++) {
                poolExec.submit(() -> {
                    try {
                        start.await();
                        service.swap(new SwapRequest("broke", ETH, USDC,
                                PoolService.SIDE_BUY_BASE, new BigDecimal("100"), null, null));
                        success.incrementAndGet();
                    } catch (ApiException ex) {
                        if (ex.getErrorCode() == ErrorCode.INSUFFICIENT_BALANCE) {
                            insufficient.incrementAndGet();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            poolExec.shutdown();

            assertTrue(success.get() <= 1, "150 USDC 最多只能成交 1 笔 100 的兑换，实际成交 " + success.get());
            assertEquals(n, success.get() + insufficient.get(), "其余请求必须是余额不足拒绝");
            assertTrue(ledger.balanceOf("broke", USDC).compareTo(BigDecimal.ZERO) >= 0,
                    "账户余额绝不能为负");
        }
    }

    @Nested
    @DisplayName("并发流动性操作")
    class ConcurrentLiquidity {

        @Test
        @DisplayName("100 个新 LP 并发注入：每人记账份额与储备增量一致，总份额变化等于各人份额之和")
        void parallelAdd() throws Exception {
            int n = 100;
            ExecutorService poolExec = Executors.newFixedThreadPool(20);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            AtomicInteger failures = new AtomicInteger();
            ConcurrentLinkedQueue<LiquidityResponse> responses = new ConcurrentLinkedQueue<>();

            BigDecimal totalSharesBefore = pool().getTotalShares();
            for (int i = 0; i < n; i++) {
                String lp = "newlp-" + i;
                ledger.credit(lp, ETH, new BigDecimal("10"));
                ledger.credit(lp, USDC, new BigDecimal("20000"));
                poolExec.submit(() -> {
                    try {
                        start.await();
                        responses.add(service.addLiquidity(new AddLiquidityRequest(lp, ETH, USDC,
                                new BigDecimal("10"), new BigDecimal("20000"), null, null, null)));
                    } catch (Throwable t) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            poolExec.shutdown();

            assertEquals(0, failures.get());
            BigDecimal sumMinted = responses.stream().map(LiquidityResponse::shares).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalSharesAfter = pool().getTotalShares();
            // 总份额增量 == 所有人新份额之和（锁死份额只在首笔时存在，增量必须严格相等）
            assertEquals(0, totalSharesAfter.subtract(totalSharesBefore).compareTo(sumMinted),
                    "总份额增量与 LP 记账之和不一致");

            String poolAccount = Ledger.poolAccount(ETH, USDC);
            assertEquals(0, ledger.balanceOf(poolAccount, ETH).compareTo(pool().getReserveBase()));
            assertEquals(0, ledger.balanceOf(poolAccount, USDC).compareTo(pool().getReserveQuote()));
        }

        @Test
        @DisplayName("同一 LP 并发赎回其全部份额两次：只能有一笔成功，不能超赎")
        void parallelRemoveNoOverRedeem() throws Exception {
            ledger.credit("rich", ETH, new BigDecimal("100"));
            ledger.credit("rich", USDC, new BigDecimal("2000000"));
            LiquidityResponse add = service.addLiquidity(new AddLiquidityRequest("rich", ETH, USDC,
                    new BigDecimal("100"), new BigDecimal("2000000"), null, null, null));

            int n = 16;
            ExecutorService poolExec = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();

            for (int i = 0; i < n; i++) {
                poolExec.submit(() -> {
                    try {
                        start.await();
                        service.removeLiquidity(new RemoveLiquidityRequest("rich", ETH, USDC, add.shares(), null));
                        success.incrementAndGet();
                    } catch (ApiException ex) {
                        if (ex.getErrorCode() == ErrorCode.INSUFFICIENT_LIQUIDITY) {
                            rejected.incrementAndGet();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            poolExec.shutdown();

            assertEquals(1, success.get(), "全部份额只可能赎回成功一次");
            assertEquals(n - 1, rejected.get(), "其余并发赎回必须被拒绝");
            // 储备不能变负
            assertTrue(pool().getReserveBase().signum() >= 0);
            assertTrue(pool().getReserveQuote().signum() >= 0);
        }
    }

    @Nested
    @DisplayName("幂等重试")
    class Idempotency {

        @Test
        @DisplayName("同一 requestId 并发/连续重试：只执行一次、只扣一次款，回放结果与首次完全一致")
        void replaySameResult() {
            ledger.credit("idem", USDC, new BigDecimal("10000"));
            SwapRequest req = new SwapRequest("idem", ETH, USDC,
                    PoolService.SIDE_BUY_BASE, new BigDecimal("500"), null, "idem-key-1");

            SwapResponse first = service.swap(req);
            for (int i = 0; i < 5; i++) {
                SwapResponse replay = service.swap(req);
                assertTrue(replay.replayed(), "重试必须标记 replayed=true");
                assertEquals(0, replay.amountOut().compareTo(first.amountOut()), "到账数量必须一致");
                assertEquals(0, replay.reserveBaseAfter().compareTo(first.reserveBaseAfter()), "储备快照必须一致");
                assertEquals(0, replay.reserveQuoteAfter().compareTo(first.reserveQuoteAfter()));
            }
            // 只扣了一次 500
            assertEquals(0, ledger.balanceOf("idem", USDC).compareTo(new BigDecimal("9500")),
                    "重试不能重复扣款");
            // 只到账一次
            assertEquals(0, ledger.balanceOf("idem", ETH).compareTo(first.amountOut()));
        }

        @Test
        @DisplayName("同一 requestId 并发提交：仅一笔真正执行，其余全部回放且扣款一次")
        void concurrentSameRequestId() throws Exception {
            ledger.credit("idem2", USDC, new BigDecimal("10000"));
            int n = 20;
            ExecutorService poolExec = Executors.newFixedThreadPool(10);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            AtomicInteger first = new AtomicInteger();
            AtomicInteger replay = new AtomicInteger();
            ConcurrentLinkedQueue<SwapResponse> outputs = new ConcurrentLinkedQueue<>();

            for (int i = 0; i < n; i++) {
                poolExec.submit(() -> {
                    try {
                        start.await();
                        SwapResponse r = service.swap(new SwapRequest("idem2", ETH, USDC,
                                PoolService.SIDE_BUY_BASE, new BigDecimal("700"), null, "idem-key-2"));
                        outputs.add(r);
                        if (r.replayed()) {
                            replay.incrementAndGet();
                        } else {
                            first.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            poolExec.shutdown();

            assertEquals(1, first.get(), "只有一笔是首次执行");
            assertEquals(n - 1, replay.get(), "其余全部幂等回放");
            assertEquals(0, ledger.balanceOf("idem2", USDC).compareTo(new BigDecimal("9300")), "只扣一次 700");
            BigDecimal ethTotal = outputs.stream().findFirst().orElseThrow().amountOut();
            assertEquals(0, ledger.balanceOf("idem2", ETH).compareTo(ethTotal), "只入账一次 ETH");
        }

        @Test
        @DisplayName("同 requestId 不同参数 -> IDEMPOTENCY_KEY_CONFLICT，且不执行新请求")
        void keyConflict() {
            ledger.credit("idem3", USDC, new BigDecimal("10000"));
            service.swap(new SwapRequest("idem3", ETH, USDC,
                    PoolService.SIDE_BUY_BASE, new BigDecimal("500"), null, "idem-key-3"));
            BigDecimal usdcBefore = ledger.balanceOf("idem3", USDC);

            ApiException ex = assertThrows(ApiException.class, () ->
                    service.swap(new SwapRequest("idem3", ETH, USDC,
                            PoolService.SIDE_BUY_BASE, new BigDecimal("600"), null, "idem-key-3")));
            assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.getErrorCode());
            assertEquals(0, ledger.balanceOf("idem3", USDC).compareTo(usdcBefore), "冲突请求不能扣款");
        }

        @Test
        @DisplayName("首次请求失败（余额不足）不占坑，同 requestId 充值后同参可成功")
        void failedRequestCanRetry() {
            ApiException ex = assertThrows(ApiException.class, () ->
                    service.swap(new SwapRequest("idem4", ETH, USDC,
                            PoolService.SIDE_BUY_BASE, new BigDecimal("500"), null, "idem-key-4")));
            assertEquals(ErrorCode.INSUFFICIENT_BALANCE, ex.getErrorCode());

            ledger.credit("idem4", USDC, new BigDecimal("1000"));
            SwapResponse resp = service.swap(new SwapRequest("idem4", ETH, USDC,
                    PoolService.SIDE_BUY_BASE, new BigDecimal("500"), null, "idem-key-4"));
            assertTrue(!resp.replayed());
            assertTrue(resp.amountOut().signum() > 0);
        }
    }
}
