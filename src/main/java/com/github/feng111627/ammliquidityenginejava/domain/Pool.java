package com.github.feng111627.ammliquidityenginejava.domain;

import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;
import com.github.feng111627.ammliquidityenginejava.pool.AmmMath;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 单个交易对的资金池。
 *
 * <p>保存：</p>
 * <ul>
 *   <li>{@code reserveBase} / {@code reserveQuote}：两种代币储备（同时也是账本里池账户余额的镜像）；</li>
 *   <li>{@code totalShares}：LP 总份额（含永久锁死的 {@link AmmMath#MINIMUM_LIQUIDITY}）；</li>
 *   <li>{@code lpShares}：每个 LP 的份额；</li>
 *   <li>{@code initialized}：是否已有首笔流动性。</li>
 * </ul>
 *
 * <p>所有状态变更只允许在持有 {@link #lock}（由 PoolRegistry 提供的池级互斥锁）
 * 的情况下进行，从而保证同一交易对的兑换与流动性操作完全串行。</p>
 */
public class Pool {

    private final Pair pair;
    private final Object lock = new Object();

    private boolean initialized = false;
    private BigDecimal reserveBase = BigDecimal.ZERO.setScale(AmmMath.SCALE);
    private BigDecimal reserveQuote = BigDecimal.ZERO.setScale(AmmMath.SCALE);
    private BigDecimal totalShares = BigDecimal.ZERO.setScale(AmmMath.SCALE);
    private final Map<String, BigDecimal> lpShares = new HashMap<>();

    public Pool(Pair pair) {
        this.pair = pair;
    }

    public Pair getPair() {
        return pair;
    }

    public Object getLock() {
        return lock;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public BigDecimal getReserveBase() {
        return reserveBase;
    }

    public BigDecimal getReserveQuote() {
        return reserveQuote;
    }

    public BigDecimal getTotalShares() {
        return totalShares;
    }

    public BigDecimal sharesOf(String userId) {
        return lpShares.getOrDefault(userId, BigDecimal.ZERO.setScale(AmmMath.SCALE));
    }

    public Map<String, BigDecimal> snapshotLpShares() {
        return new HashMap<>(lpShares);
    }

    /**
     * 首笔流动性：写入储备，铸造份额（最小流动性永久锁死在零地址）。
     *
     * @return 实际发给 LP 的份额
     */
    public BigDecimal initialize(String userId, BigDecimal baseIn, BigDecimal quoteIn) {
        BigDecimal[] shares = AmmMath.initialShares(baseIn, quoteIn);
        BigDecimal mintable = shares[0];
        this.reserveBase = baseIn;
        this.reserveQuote = quoteIn;
        this.totalShares = shares[1];
        // 死锁的最小份额记在零地址名下，永不参与赎回
        this.lpShares.put(LOCKED_HOLDER, AmmMath.MINIMUM_LIQUIDITY);
        if (mintable.signum() > 0) {
            this.lpShares.put(userId, mintable);
        }
        this.initialized = true;
        return mintable;
    }

    /**
     * 追加流动性：增加储备并给 LP 增发份额。调用方需保证入参与当前池价格匹配。
     */
    public BigDecimal mintShares(String userId,
                                 BigDecimal baseIn,
                                 BigDecimal quoteIn,
                                 BigDecimal sharesOut) {
        if (sharesOut.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    "按当前注入比例计算得到的 LP 份额为 0，请增加注入数量");
        }
        this.reserveBase = this.reserveBase.add(baseIn);
        this.reserveQuote = this.reserveQuote.add(quoteIn);
        this.totalShares = this.totalShares.add(sharesOut);
        this.lpShares.merge(userId, sharesOut, BigDecimal::add);
        return sharesOut;
    }

    /**
     * 赎回流动性：按份额比例扣减储备、销毁份额。返回 [baseOut, quoteOut]。
     * 份额与储备的对应关系保持为：赎回量 = floor(shares/total * reserve)，
     * 销毁份额后剩余储备/剩余份额 >= 赎回前均价。
     */
    public BigDecimal[] burnShares(String userId, BigDecimal sharesIn) {
        BigDecimal held = sharesOf(userId);
        if (sharesIn.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT, "赎回份额必须为正数：" + sharesIn);
        }
        if (held.compareTo(sharesIn) < 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_LIQUIDITY,
                    "LP " + userId + " 持有份额 " + held.stripTrailingZeros().toPlainString()
                            + "，不足以赎回 " + sharesIn.stripTrailingZeros().toPlainString());
        }
        BigDecimal baseOut = AmmMath.redeemAmount(sharesIn, reserveBase, totalShares);
        BigDecimal quoteOut = AmmMath.redeemAmount(sharesIn, reserveQuote, totalShares);

        this.reserveBase = this.reserveBase.subtract(baseOut);
        this.reserveQuote = this.reserveQuote.subtract(quoteOut);
        this.totalShares = this.totalShares.subtract(sharesIn);
        BigDecimal left = held.subtract(sharesIn);
        if (left.signum() == 0) {
            this.lpShares.remove(userId);
        } else {
            this.lpShares.put(userId, left);
        }
        return new BigDecimal[]{baseOut, quoteOut};
    }

    /**
     * 兑换后更新储备：输入侧增加，输出侧减少。
     */
    public void applySwap(BigDecimal amountIn, BigDecimal amountOut, boolean buyBase) {
        if (buyBase) {
            // 用 quote 买 base：quote 储备增加，base 储备减少
            this.reserveQuote = this.reserveQuote.add(amountIn);
            this.reserveBase = this.reserveBase.subtract(amountOut);
        } else {
            // 卖 base 换 quote
            this.reserveBase = this.reserveBase.add(amountIn);
            this.reserveQuote = this.reserveQuote.subtract(amountOut);
        }
    }

    /** 永久锁死最小流动性份额的持有人名。 */
    public static final String LOCKED_HOLDER = "0x0-locked";
}
