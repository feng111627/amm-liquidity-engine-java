package com.github.feng111627.ammliquidityenginejava.amm.model;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 交易对及其资金池状态（内存账本）。
 * 所有对状态的读写都必须在 {@link #lock} 保护下进行。
 */
public class TradingPair {

    private final String pairId;
    private final String tokenA;
    private final String tokenB;

    /** 池内 tokenA 储备 */
    private BigInteger reserveA = BigInteger.ZERO;
    /** 池内 tokenB 储备 */
    private BigInteger reserveB = BigInteger.ZERO;
    /** LP 份额总量 */
    private BigInteger totalShares = BigInteger.ZERO;

    /** 每个 LP 地址持有的份额 */
    private final Map<String, BigInteger> sharesOf = new LinkedHashMap<>();

    /** 同一交易对上的兑换/流动性操作串行化 */
    private final ReentrantLock lock = new ReentrantLock();

    /** 幂等表：idempotencyKey -> 已执行结果 */
    private final Map<String, Object> idempotencyLog = new LinkedHashMap<>();

    public TradingPair(String pairId, String tokenA, String tokenB) {
        this.pairId = pairId;
        this.tokenA = tokenA;
        this.tokenB = tokenB;
    }

    public String getPairId() { return pairId; }
    public String getTokenA() { return tokenA; }
    public String getTokenB() { return tokenB; }

    public BigInteger getReserveA() { return reserveA; }
    public void setReserveA(BigInteger reserveA) { this.reserveA = reserveA; }

    public BigInteger getReserveB() { return reserveB; }
    public void setReserveB(BigInteger reserveB) { this.reserveB = reserveB; }

    public BigInteger getTotalShares() { return totalShares; }
    public void setTotalShares(BigInteger totalShares) { this.totalShares = totalShares; }

    public Map<String, BigInteger> getSharesOf() { return sharesOf; }

    public Map<String, Object> getIdempotencyLog() { return idempotencyLog; }

    public void lock() { lock.lock(); }

    public void unlock() { lock.unlock(); }
}
