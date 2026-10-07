package com.github.feng111627.ammliquidityenginejava.pool;

import com.github.feng111627.ammliquidityenginejava.domain.Pair;
import com.github.feng111627.ammliquidityenginejava.domain.Pool;
import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;
import com.github.feng111627.ammliquidityenginejava.ledger.Ledger;
import com.github.feng111627.ammliquidityenginejava.web.dto.AddLiquidityRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.CreatePairRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.LiquidityResponse;
import com.github.feng111627.ammliquidityenginejava.web.dto.PoolView;
import com.github.feng111627.ammliquidityenginejava.web.dto.QuoteResponse;
import com.github.feng111627.ammliquidityenginejava.web.dto.RemoveLiquidityRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.SwapRequest;
import com.github.feng111627.ammliquidityenginejava.web.dto.SwapResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

/**
 * AMM 核心业务服务：建池、兑换、注入/赎回流动性、行情预览。
 *
 * <p>并发模型：同一交易对的所有状态变更都在 {@code pool.getLock()} 内串行执行；
 * 带 requestId 的请求再套一层 id 级锁（顺序固定 id锁 -> 池锁），实现幂等重试。</p>
 */
@Service
public class PoolService {

    private static final Logger log = LoggerFactory.getLogger(PoolService.class);

    /** 固定手续费率 0.3%，手续费留在池内，随 k 增长体现在 LP 份额价值上。 */
    public static final BigDecimal FEE_RATE = new BigDecimal("0.003");

    /** 用 quote 买入 base。 */
    public static final String SIDE_BUY_BASE = "BUY_BASE";
    /** 卖出 base 换 quote。 */
    public static final String SIDE_SELL_BASE = "SELL_BASE";

    private final PoolRegistry registry;
    private final Ledger ledger;
    private final IdempotencyStore idempotencyStore;

    public PoolService(PoolRegistry registry, Ledger ledger, IdempotencyStore idempotencyStore) {
        this.registry = registry;
        this.ledger = ledger;
        this.idempotencyStore = idempotencyStore;
    }

    // ---------------------------------------------------------------- 交易对

    public PoolView createPair(CreatePairRequest request) {
        Pair pair = toPair(request.base(), request.quote());
        Pool pool = registry.create(pair);
        log.info("创建交易对 {}", pair.key());
        return toView(pool);
    }

    public List<PoolView> listPools() {
        return registry.all().stream().map(this::toView).toList();
    }

    public PoolView getPool(String base, String quote) {
        return toView(registry.require(toPair(base, quote)));
    }

    // ---------------------------------------------------------------- 行情

    public QuoteResponse quote(String base, String quote, String side, String amount) {
        Pair pair = toPair(base, quote);
        Pool pool = registry.require(pair);
        if (!pool.isInitialized()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_LIQUIDITY,
                    "资金池尚未注入初始流动性：" + pair.key());
        }
        boolean buyBase = parseSide(side);
        BigDecimal amountIn = Amounts.requirePositive(parseDecimal(amount), "amount");

        BigDecimal reserveIn = buyBase ? pool.getReserveQuote() : pool.getReserveBase();
        BigDecimal reserveOut = buyBase ? pool.getReserveBase() : pool.getReserveQuote();
        BigDecimal amountOut = AmmMath.outputAmount(amountIn, reserveIn, reserveOut, FEE_RATE);
        BigDecimal feePaid = amountIn.multiply(FEE_RATE).setScale(AmmMath.SCALE, AmmMath.ROUNDING);
        String tokenIn = buyBase ? pair.getQuote() : pair.getBase();
        String tokenOut = buyBase ? pair.getBase() : pair.getQuote();
        return new QuoteResponse(side.toUpperCase(), tokenIn, tokenOut, amountIn, amountOut, feePaid, FEE_RATE);
    }

    // ---------------------------------------------------------------- 注入

    public LiquidityResponse addLiquidity(AddLiquidityRequest request) {
        requireUser(request.userId());
        Pair pair = toPair(request.base(), request.quote());
        BigDecimal baseIn = Amounts.requirePositive(request.baseAmount(), "baseAmount");
        BigDecimal quoteIn = Amounts.requirePositive(request.quoteAmount(), "quoteAmount");
        BigDecimal minBase = Amounts.optionalMin(request.minBase());
        BigDecimal minQuote = Amounts.optionalMin(request.minQuote());

        String payload = "ADD|" + pair.key() + "|" + request.userId() + "|"
                + baseIn.toPlainString() + "|" + quoteIn.toPlainString()
                + "|" + minBase.toPlainString() + "|" + minQuote.toPlainString();

        return runIdempotent(request.requestId(), payload, () -> doAdd(request.userId(), pair,
                baseIn, quoteIn, minBase, minQuote, request.requestId()));
    }

    private LiquidityResponse doAdd(String userId, Pair pair,
                                    BigDecimal baseIn, BigDecimal quoteIn,
                                    BigDecimal minBase, BigDecimal minQuote, String requestId) {
        synchronized (registry.require(pair).getLock()) {
            Pool pool = registry.require(pair);
            String poolAccount = Ledger.poolAccount(pair.getBase(), pair.getQuote());

            // ---- 1. 纯计算阶段：确定实际使用量与份额，不碰任何状态 ----
            BigDecimal sharesOut;
            BigDecimal usedBase;
            BigDecimal usedQuote;
            if (!pool.isInitialized()) {
                usedBase = baseIn;
                usedQuote = quoteIn;
                BigDecimal[] first = AmmMath.initialShares(usedBase, usedQuote);
                sharesOut = first[0];
                if (sharesOut.signum() <= 0) {
                    throw new ApiException(ErrorCode.INVALID_AMOUNT,
                            "初始注入量太小，铸造份额为 0，请增加注入数量");
                }
            } else {
                // 按池内当前比例，取更吃亏一侧的实际使用量，多余的划转随后退还用户
                BigDecimal optimalQuote = quoteForBase(baseIn, pool.getReserveBase(), pool.getReserveQuote());
                if (optimalQuote.compareTo(quoteIn) <= 0) {
                    usedBase = baseIn;
                    usedQuote = optimalQuote;
                } else {
                    usedQuote = quoteIn;
                    usedBase = baseForQuote(quoteIn, pool.getReserveBase(), pool.getReserveQuote());
                }
                sharesOut = AmmMath.addShares(usedBase, usedQuote,
                        pool.getReserveBase(), pool.getReserveQuote(), pool.getTotalShares());
                if (sharesOut.signum() <= 0) {
                    throw new ApiException(ErrorCode.INVALID_AMOUNT,
                            "按当前注入比例计算得到的 LP 份额为 0，请增加注入数量");
                }
            }

            // ---- 2. 滑点保护：实际注入量低于下限，在动钱之前整笔拒绝 ----
            if (usedBase.compareTo(minBase) < 0 || usedQuote.compareTo(minQuote) < 0) {
                throw new ApiException(ErrorCode.SLIPPAGE_PROTECTION,
                        "实际注入量低于下限：base=" + usedBase.toPlainString() + "，下限=" + minBase.toPlainString()
                                + "；quote=" + usedQuote.toPlainString() + "，下限=" + minQuote.toPlainString());
            }

            // ---- 3. 划转 + 落状态（余额不足会抛 INSUFFICIENT_BALANCE，池状态未动）----
            ledger.transfer(userId, poolAccount, pair.getBase(), baseIn);
            ledger.transfer(userId, poolAccount, pair.getQuote(), quoteIn);
            if (!pool.isInitialized()) {
                pool.initialize(userId, usedBase, usedQuote);
            } else {
                pool.mintShares(userId, usedBase, usedQuote, sharesOut);
            }
            if (usedBase.compareTo(baseIn) < 0) {
                ledger.transfer(poolAccount, userId, pair.getBase(), baseIn.subtract(usedBase));
            }
            if (usedQuote.compareTo(quoteIn) < 0) {
                ledger.transfer(poolAccount, userId, pair.getQuote(), quoteIn.subtract(usedQuote));
            }

            assertReservesMatchLedger(pool, pair);
            log.info("LP {} 向 {} 注入 {}/{}，获得份额 {}（总份额 {}）",
                    userId, pair.key(), usedBase.toPlainString(), usedQuote.toPlainString(),
                    sharesOut.toPlainString(), pool.getTotalShares().toPlainString());
            return new LiquidityResponse("ADD", userId, pair.getBase(), pair.getQuote(),
                    sharesOut, pool.getTotalShares(), usedBase, usedQuote,
                    pool.getReserveBase(), pool.getReserveQuote(), requestId, false);
        }
    }

    // ---------------------------------------------------------------- 赎回

    public LiquidityResponse removeLiquidity(RemoveLiquidityRequest request) {
        requireUser(request.userId());
        Pair pair = toPair(request.base(), request.quote());
        BigDecimal sharesIn = Amounts.requirePositive(request.shares(), "shares");
        String payload = "REMOVE|" + pair.key() + "|" + request.userId() + "|" + sharesIn.toPlainString();

        return runIdempotent(request.requestId(), payload, () -> doRemove(request.userId(), pair,
                sharesIn, request.requestId()));
    }

    private LiquidityResponse doRemove(String userId, Pair pair, BigDecimal sharesIn, String requestId) {
        synchronized (registry.require(pair).getLock()) {
            Pool pool = registry.require(pair);
            if (!pool.isInitialized()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_LIQUIDITY, "资金池尚无流动性：" + pair.key());
            }
            String poolAccount = Ledger.poolAccount(pair.getBase(), pair.getQuote());

            BigDecimal[] out = pool.burnShares(userId, sharesIn);
            BigDecimal baseOut = out[0];
            BigDecimal quoteOut = out[1];
            if (baseOut.signum() == 0 && quoteOut.signum() == 0) {
                throw new ApiException(ErrorCode.INVALID_AMOUNT,
                        "赎回份额过小，按比例取回数量为 0：" + sharesIn.toPlainString());
            }
            if (baseOut.signum() > 0) {
                ledger.transfer(poolAccount, userId, pair.getBase(), baseOut);
            }
            if (quoteOut.signum() > 0) {
                ledger.transfer(poolAccount, userId, pair.getQuote(), quoteOut);
            }
            assertReservesMatchLedger(pool, pair);
            log.info("LP {} 从 {} 赎回份额 {}，取回 {}/{}（剩余总份额 {}）",
                    userId, pair.key(), sharesIn.toPlainString(),
                    baseOut.toPlainString(), quoteOut.toPlainString(),
                    pool.getTotalShares().toPlainString());
            return new LiquidityResponse("REMOVE", userId, pair.getBase(), pair.getQuote(),
                    sharesIn, pool.getTotalShares(), baseOut, quoteOut,
                    pool.getReserveBase(), pool.getReserveQuote(), requestId, false);
        }
    }

    // ---------------------------------------------------------------- 兑换

    public SwapResponse swap(SwapRequest request) {
        requireUser(request.userId());
        Pair pair = toPair(request.base(), request.quote());
        boolean buyBase = parseSide(request.side());
        BigDecimal amountIn = Amounts.requirePositive(request.amountIn(), "amountIn");
        BigDecimal minOut = Amounts.optionalMin(request.minAmountOut());

        String tokenIn = buyBase ? pair.getQuote() : pair.getBase();
        String payload = "SWAP|" + pair.key() + "|" + request.userId() + "|"
                + (buyBase ? SIDE_BUY_BASE : SIDE_SELL_BASE) + "|"
                + amountIn.toPlainString() + "|" + minOut.toPlainString();

        return runIdempotent(request.requestId(), payload,
                () -> doSwap(request.userId(), pair, buyBase, amountIn, minOut, tokenIn, request.requestId()));
    }

    private SwapResponse doSwap(String userId, Pair pair, boolean buyBase,
                                BigDecimal amountIn, BigDecimal minOut, String tokenIn, String requestId) {
        synchronized (registry.require(pair).getLock()) {
            Pool pool = registry.require(pair);
            if (!pool.isInitialized()) {
                throw new ApiException(ErrorCode.INSUFFICIENT_LIQUIDITY, "资金池尚无流动性：" + pair.key());
            }
            String poolAccount = Ledger.poolAccount(pair.getBase(), pair.getQuote());
            String tokenOut = buyBase ? pair.getBase() : pair.getQuote();

            BigDecimal reserveIn = buyBase ? pool.getReserveQuote() : pool.getReserveBase();
            BigDecimal reserveOut = buyBase ? pool.getReserveBase() : pool.getReserveQuote();

            BigDecimal amountOut = AmmMath.outputAmount(amountIn, reserveIn, reserveOut, FEE_RATE);

            // 拒绝原因区分 1：池储备不够（理论上 amountOut<reserveOut 恒成立，
            // 这里把输出为 0 的极端情况也归入储备/流动性不足）
            if (amountOut.signum() == 0 || amountOut.compareTo(reserveOut) >= 0) {
                throw new ApiException(ErrorCode.INSUFFICIENT_POOL_RESERVE,
                        "资金池 " + tokenOut + " 储备不足以完成该兑换：储备 "
                                + reserveOut.stripTrailingZeros().toPlainString());
            }
            // 拒绝原因区分 2：滑点保护
            if (amountOut.compareTo(minOut) < 0) {
                throw new ApiException(ErrorCode.SLIPPAGE_PROTECTION,
                        "预计到账 " + amountOut.toPlainString() + " " + tokenOut
                                + " 低于下限 " + minOut.toPlainString());
            }

            // 先扣用户余额（余额不足抛 INSUFFICIENT_BALANCE），再入账池并更新储备
            ledger.transfer(userId, poolAccount, tokenIn, amountIn);
            ledger.transfer(poolAccount, userId, tokenOut, amountOut);
            pool.applySwap(amountIn, amountOut, buyBase);
            assertReservesMatchLedger(pool, pair);

            BigDecimal feePaid = amountIn.multiply(FEE_RATE).setScale(AmmMath.SCALE, AmmMath.ROUNDING);
            log.info("用户 {} 在 {} {}：付出 {} {}，得到 {} {}，手续费沉淀 {} {}",
                    userId, pair.key(), buyBase ? SIDE_BUY_BASE : SIDE_SELL_BASE,
                    amountIn.toPlainString(), tokenIn, amountOut.toPlainString(), tokenOut,
                    feePaid.toPlainString(), tokenIn);
            return new SwapResponse(buyBase ? SIDE_BUY_BASE : SIDE_SELL_BASE,
                    tokenIn, tokenOut, amountIn, amountOut, feePaid,
                    pool.getReserveBase(), pool.getReserveQuote(), requestId, false);
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 幂等执行包装：
     * 无 requestId 时直接执行；有 requestId 时在 id 锁内判断“回放 / 指纹冲突 / 首次执行”。
     */
    @SuppressWarnings("unchecked")
    private <T> T runIdempotent(String requestId, String payloadKey, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            return action.get();
        }
        synchronized (idempotencyStore.guardOf(requestId)) {
            var existing = idempotencyStore.find(requestId);
            if (existing.isPresent()) {
                IdempotencyStore.Entry entry = idempotencyStore.replayOrReject(requestId, payloadKey);
                Object result = entry.result();
                if (result == null) {
                    // 首次执行进程尚未写结果（异常中断），允许同参重试
                    idempotencyStore.release(requestId);
                } else {
                    log.info("幂等回放 requestId={}", requestId);
                    T replayed = (T) result;
                    return markReplayed(replayed);
                }
            }
            idempotencyStore.occupy(requestId, payloadKey);
            try {
                T result = action.get();
                idempotencyStore.complete(requestId, result);
                return result;
            } catch (RuntimeException ex) {
                // 业务失败不占坑，允许同 requestId 同参数重试
                idempotencyStore.release(requestId);
                throw ex;
            }
        }
    }

    /** 把首次结果复制一份并把 replayed 标记翻为 true（DTO 均为 record）。 */
    @SuppressWarnings("unchecked")
    private static <T> T markReplayed(T result) {
        return switch (result) {
            case SwapResponse r -> (T) new SwapResponse(r.side(), r.tokenIn(), r.tokenOut(),
                    r.amountIn(), r.amountOut(), r.feePaid(),
                    r.reserveBaseAfter(), r.reserveQuoteAfter(), r.requestId(), true);
            case LiquidityResponse r -> (T) new LiquidityResponse(r.type(), r.userId(), r.base(), r.quote(),
                    r.shares(), r.totalShares(), r.baseAmount(), r.quoteAmount(),
                    r.reserveBaseAfter(), r.reserveQuoteAfter(), r.requestId(), true);
            default -> result;
        };
    }

    private static BigDecimal quoteForBase(BigDecimal baseIn, BigDecimal reserveBase, BigDecimal reserveQuote) {
        // floor(baseIn * reserveQuote / reserveBase)
        return baseIn.multiply(reserveQuote).divide(reserveBase, AmmMath.SCALE, AmmMath.ROUNDING);
    }

    private static BigDecimal baseForQuote(BigDecimal quoteIn, BigDecimal reserveBase, BigDecimal reserveQuote) {
        return quoteIn.multiply(reserveBase).divide(reserveQuote, AmmMath.SCALE, AmmMath.ROUNDING);
    }

    /**
     * 不变量校验：池状态中的储备必须恒等于账本里池账户的对应余额。
     */
    private void assertReservesMatchLedger(Pool pool, Pair pair) {
        String poolAccount = Ledger.poolAccount(pair.getBase(), pair.getQuote());
        BigDecimal ledgerBase = ledger.balanceOf(poolAccount, pair.getBase());
        BigDecimal ledgerQuote = ledger.balanceOf(poolAccount, pair.getQuote());
        if (ledgerBase.compareTo(pool.getReserveBase()) != 0
                || ledgerQuote.compareTo(pool.getReserveQuote()) != 0) {
            throw new IllegalStateException("储备与账本不一致：pool=" + pair.key()
                    + " 储备(" + pool.getReserveBase() + "," + pool.getReserveQuote()
                    + ") 账本(" + ledgerBase + "," + ledgerQuote + ")");
        }
    }

    private PoolView toView(Pool pool) {
        return new PoolView(pool.getPair().getBase(), pool.getPair().getQuote(),
                pool.getReserveBase(), pool.getReserveQuote(), pool.getTotalShares(), FEE_RATE);
    }

    private static Pair toPair(String base, String quote) {
        try {
            return new Pair(base, quote);
        } catch (IllegalArgumentException ex) {
            ErrorCode code = ex.getMessage() != null && ex.getMessage().contains("不能相同")
                    ? ErrorCode.SAME_TOKEN_NOT_ALLOWED : ErrorCode.INVALID_AMOUNT;
            throw new ApiException(code, ex.getMessage());
        }
    }

    private static boolean parseSide(String side) {
        if (SIDE_BUY_BASE.equalsIgnoreCase(side)) {
            return true;
        }
        if (SIDE_SELL_BASE.equalsIgnoreCase(side)) {
            return false;
        }
        throw new ApiException(ErrorCode.INVALID_AMOUNT,
                "side 只能是 BUY_BASE 或 SELL_BASE，实际为：" + side);
    }

    private static BigDecimal parseDecimal(String text) {
        try {
            return new BigDecimal(text);
        } catch (RuntimeException ex) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "金额格式非法：" + text);
        }
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "userId 不能为空");
        }
    }
}
