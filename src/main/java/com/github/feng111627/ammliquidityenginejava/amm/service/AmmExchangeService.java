package com.github.feng111627.ammliquidityenginejava.amm.service;

import com.github.feng111627.ammliquidityenginejava.amm.exception.InsufficientLiquidityException;
import com.github.feng111627.ammliquidityenginejava.amm.exception.PairAlreadyExistsException;
import com.github.feng111627.ammliquidityenginejava.amm.exception.PairNotFoundException;
import com.github.feng111627.ammliquidityenginejava.amm.exception.SlippageExceededException;
import com.github.feng111627.ammliquidityenginejava.amm.model.AddLiquidityResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.RemoveLiquidityResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.SwapResult;
import com.github.feng111627.ammliquidityenginejava.amm.model.TradingPair;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版自动做市（AMM）交易所核心服务。
 *
 * <p>定价：恒定乘积 x * y = k。兑换时先把固定比例手续费从入账中扣下并留在池内，
 * 剩余部分按 k 不变推出到账数量（与 Uniswap V2 的整数公式一致）。</p>
 *
 * <p>并发：同一交易对上的所有状态变更都在该交易对的锁内串行执行；
 * 幂等：同一 idempotencyKey 在同一交易对上只执行一次，重试直接返回首次结果。</p>
 */
@Service
public class AmmExchangeService {

    /** 手续费率，万分比。30 / 10000 = 0.3% */
    public static final long FEE_NUMERATOR = 30;
    public static final long FEE_DENOMINATOR = 10_000;

    private static final BigInteger FEE_NUM = BigInteger.valueOf(FEE_NUMERATOR);
    private static final BigInteger FEE_DEN = BigInteger.valueOf(FEE_DENOMINATOR);
    private static final BigInteger EFFECTIVE_NUM = FEE_DEN.subtract(FEE_NUM);

    private final Map<String, TradingPair> pairs = new ConcurrentHashMap<>();

    /** 创建交易对（初始池为空，等待首次注入流动性） */
    public TradingPair createPair(String pairId, String tokenA, String tokenB) {
        requireNonBlank(pairId, "pairId");
        requireNonBlank(tokenA, "tokenA");
        requireNonBlank(tokenB, "tokenB");
        if (tokenA.equals(tokenB)) {
            throw new IllegalArgumentException("交易对两侧必须是不同的代币: " + tokenA);
        }
        TradingPair pair = new TradingPair(pairId, tokenA, tokenB);
        TradingPair existing = pairs.putIfAbsent(pairId, pair);
        if (existing != null) {
            throw new PairAlreadyExistsException(pairId);
        }
        return pair;
    }

    /**
     * 兑换。tokenIn 指定支付方向，amountIn 为支付数量，
     * minAmountOut 为滑点保护下限（最少到账数量）。
     *
     * @throws InsufficientLiquidityException 池子储备不足，按当前公式无法成交
     * @throws SlippageExceededException      可成交但到账低于 minAmountOut
     */
    public SwapResult swap(String pairId, String tokenIn,
                           BigInteger amountIn, BigInteger minAmountOut,
                           String idempotencyKey) {
        TradingPair pair = getPair(pairId);
        requirePositive(amountIn, "amountIn");
        if (minAmountOut == null || minAmountOut.signum() < 0) {
            throw new IllegalArgumentException("minAmountOut 不能为负");
        }
        requireNonBlank(idempotencyKey, "idempotencyKey");

        pair.lock();
        try {
            Object cached = pair.getIdempotencyLog().get(idempotencyKey);
            if (cached != null) {
                return (SwapResult) cached;
            }

            boolean aToB;
            if (tokenIn.equals(pair.getTokenA())) {
                aToB = true;
            } else if (tokenIn.equals(pair.getTokenB())) {
                aToB = false;
            } else {
                throw new IllegalArgumentException(
                        "tokenIn 不属于该交易对: " + tokenIn);
            }

            BigInteger reserveIn = aToB ? pair.getReserveA() : pair.getReserveB();
            BigInteger reserveOut = aToB ? pair.getReserveB() : pair.getReserveA();
            if (reserveIn.signum() <= 0 || reserveOut.signum() <= 0) {
                throw new InsufficientLiquidityException(
                        "池子为空或储备不足，无法兑换: " + pairId);
            }

            // 手续费从入账中扣除并留在池内，剩余部分按恒定乘积定价
            BigInteger amountInEffective = amountIn.multiply(EFFECTIVE_NUM);
            BigInteger numerator = amountInEffective.multiply(reserveOut);
            BigInteger denominator = reserveIn.multiply(FEE_DEN).add(amountInEffective);
            BigInteger amountOut = numerator.divide(denominator);

            if (amountOut.signum() <= 0 || amountOut.compareTo(reserveOut) >= 0) {
                throw new InsufficientLiquidityException(
                        "储备不足：按当前价格可兑出 " + amountOut
                                + "，池内仅有 " + reserveOut);
            }
            if (amountOut.compareTo(minAmountOut) < 0) {
                throw new SlippageExceededException(
                        "滑点保护触发：可到账 " + amountOut
                                + " 低于最少到账 " + minAmountOut);
            }

            BigInteger feeAmount = amountIn.multiply(FEE_NUM).divide(FEE_DEN);
            if (aToB) {
                pair.setReserveA(reserveIn.add(amountIn));
                pair.setReserveB(reserveOut.subtract(amountOut));
            } else {
                pair.setReserveB(reserveIn.add(amountIn));
                pair.setReserveA(reserveOut.subtract(amountOut));
            }

            SwapResult result = new SwapResult(
                    pairId, tokenIn, aToB ? pair.getTokenB() : pair.getTokenA(),
                    amountIn, amountOut, feeAmount,
                    pair.getReserveA(), pair.getReserveB());
            pair.getIdempotencyLog().put(idempotencyKey, result);
            return result;
        } finally {
            pair.unlock();
        }
    }

    /**
     * 注入流动性。首次注入时份额 = sqrt(amountA * amountB)；
     * 之后按两侧各自可铸份额的较小者结算（与 Uniswap V2 一致），
     * 保证份额与储备的对应关系不被稀释。
     */
    public AddLiquidityResult addLiquidity(String pairId, String provider,
                                           BigInteger amountA, BigInteger amountB,
                                           String idempotencyKey) {
        TradingPair pair = getPair(pairId);
        requireNonBlank(provider, "provider");
        requirePositive(amountA, "amountA");
        requirePositive(amountB, "amountB");
        requireNonBlank(idempotencyKey, "idempotencyKey");

        pair.lock();
        try {
            Object cached = pair.getIdempotencyLog().get(idempotencyKey);
            if (cached != null) {
                return (AddLiquidityResult) cached;
            }

            BigInteger total = pair.getTotalShares();
            BigInteger shares;
            if (total.signum() == 0) {
                shares = amountA.multiply(amountB).sqrt();
                if (shares.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "首次注入数量过小，无法铸造份额");
                }
            } else {
                BigInteger sharesA = amountA.multiply(total).divide(pair.getReserveA());
                BigInteger sharesB = amountB.multiply(total).divide(pair.getReserveB());
                shares = sharesA.min(sharesB);
                if (shares.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "注入数量相对池子过小，无法铸造份额");
                }
            }

            pair.setReserveA(pair.getReserveA().add(amountA));
            pair.setReserveB(pair.getReserveB().add(amountB));
            pair.setTotalShares(total.add(shares));
            pair.getSharesOf().merge(provider, shares, BigInteger::add);

            AddLiquidityResult result = new AddLiquidityResult(
                    pairId, provider, amountA, amountB, shares,
                    pair.getTotalShares(), pair.getReserveA(), pair.getReserveB());
            pair.getIdempotencyLog().put(idempotencyKey, result);
            return result;
        } finally {
            pair.unlock();
        }
    }

    /**
     * 赎回流动性。按份额占比等比例取回两侧储备：
     * amountOut = shares * reserve / totalShares。
     * 份额先核销再出账，不会凭空多发或少扣。
     */
    public RemoveLiquidityResult removeLiquidity(String pairId, String provider,
                                                 BigInteger shares,
                                                 String idempotencyKey) {
        TradingPair pair = getPair(pairId);
        requireNonBlank(provider, "provider");
        requirePositive(shares, "shares");
        requireNonBlank(idempotencyKey, "idempotencyKey");

        pair.lock();
        try {
            Object cached = pair.getIdempotencyLog().get(idempotencyKey);
            if (cached != null) {
                return (RemoveLiquidityResult) cached;
            }

            BigInteger balance = pair.getSharesOf().getOrDefault(provider, BigInteger.ZERO);
            if (balance.compareTo(shares) < 0) {
                throw new IllegalArgumentException(
                        "份额不足：持有 " + balance + "，尝试赎回 " + shares);
            }

            BigInteger total = pair.getTotalShares();
            BigInteger amountAOut = shares.multiply(pair.getReserveA()).divide(total);
            BigInteger amountBOut = shares.multiply(pair.getReserveB()).divide(total);

            pair.setReserveA(pair.getReserveA().subtract(amountAOut));
            pair.setReserveB(pair.getReserveB().subtract(amountBOut));
            pair.setTotalShares(total.subtract(shares));
            BigInteger remaining = balance.subtract(shares);
            if (remaining.signum() == 0) {
                pair.getSharesOf().remove(provider);
            } else {
                pair.getSharesOf().put(provider, remaining);
            }

            RemoveLiquidityResult result = new RemoveLiquidityResult(
                    pairId, provider, shares, amountAOut, amountBOut,
                    pair.getTotalShares(), pair.getReserveA(), pair.getReserveB());
            pair.getIdempotencyLog().put(idempotencyKey, result);
            return result;
        } finally {
            pair.unlock();
        }
    }

    public TradingPair getPair(String pairId) {
        TradingPair pair = pairs.get(pairId);
        if (pair == null) {
            throw new PairNotFoundException(pairId);
        }
        return pair;
    }

    public List<TradingPair> listPairs() {
        return List.copyOf(pairs.values());
    }

    private static void requirePositive(BigInteger v, String name) {
        if (v == null || v.signum() <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
    }

    private static void requireNonBlank(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
