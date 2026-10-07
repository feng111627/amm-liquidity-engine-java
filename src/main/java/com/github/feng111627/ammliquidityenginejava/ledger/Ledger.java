package com.github.feng111627.ammliquidityenginejava.ledger;

import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存账本：记录每个账户每种代币的可用余额。
 *
 * <p>账户既包括用户（userId），也包括每个资金池自身（池账户名由
 * {@link #poolAccount(String, String)} 生成）。所有资金划转必须走
 * {@link #transfer(String, String, String, BigDecimal)}，保证借贷必相等，
 * 不会凭空产生或销毁代币。</p>
 *
 * <p>余额本身是不可变 {@link BigDecimal} 替换（{@link ConcurrentHashMap#compute}
 * 原子更新），业务层另外通过每个池的互斥锁串行化同一交易对的操作。</p>
 */
@Component
public class Ledger {

    /** key = account + '\u0000' + token -> 余额 */
    private final ConcurrentHashMap<String, BigDecimal> balances = new ConcurrentHashMap<>();

    /**
     * 给账户增加余额（仅测试水龙头 / 初始化使用）。
     */
    public void credit(String account, String token, BigDecimal amount) {
        checkPositive(amount);
        balances.merge(key(account, token), normalized(amount), BigDecimal::add);
    }

    /**
     * 从账户扣减余额，余额不足抛出 {@link ErrorCode#INSUFFICIENT_BALANCE}。
     */
    public void debit(String account, String token, BigDecimal amount) {
        checkPositive(amount);
        balances.compute(key(account, token), (k, current) -> {
            BigDecimal balance = current == null ? BigDecimal.ZERO : current;
            if (balance.compareTo(amount) < 0) {
                throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE,
                        "账户 " + account + " 的 " + token + " 余额不足：需要 "
                                + amount.stripTrailingZeros().toPlainString() + "，可用 "
                                + balance.stripTrailingZeros().toPlainString());
            }
            return balance.subtract(amount);
        });
    }

    /**
     * 原子划转：from -> to。先借后贷，任何一方失败都不会留下半笔账。
     */
    public void transfer(String from, String to, String token, BigDecimal amount) {
        checkPositive(amount);
        // 同一把 map 的分段锁无法跨两个 key，这里用同步块保护两笔原子更新。
        synchronized (this) {
            debit(from, token, amount);
            credit(to, token, amount);
        }
    }

    public BigDecimal balanceOf(String account, String token) {
        return balances.getOrDefault(key(account, token), BigDecimal.ZERO.setScale(18, java.math.RoundingMode.FLOOR));
    }

    /**
     * 返回某账户全部代币余额的快照（含零余额代币不包含）。
     */
    public Map<String, BigDecimal> balancesOf(String account) {
        Map<String, BigDecimal> snapshot = new ConcurrentHashMap<>();
        String prefix = account + '\u0000';
        balances.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                snapshot.put(k.substring(prefix.length()), v);
            }
        });
        return snapshot;
    }

    public static String poolAccount(String base, String quote) {
        return LedgerKeys.POOL_ACCOUNT + ":" + base + "/" + quote;
    }

    private static String key(String account, String token) {
        return account + '\u0000' + token;
    }

    private static void checkPositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_AMOUNT,
                    "金额必须为正数：" + amount);
        }
    }

    private static BigDecimal normalized(BigDecimal amount) {
        return amount.setScale(18, java.math.RoundingMode.FLOOR);
    }
}
