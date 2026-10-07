package com.github.feng111627.ammliquidityenginejava.pool;

import com.github.feng111627.ammliquidityenginejava.domain.Pair;
import com.github.feng111627.ammliquidityenginejava.domain.Pool;
import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易对/资金池注册表。
 *
 * <p>{@link #create(Pair)} 通过 putIfAbsent 保证并发下不会重复建池；
 * 每个 {@link Pool} 自带一把互斥锁，业务层对同一交易对的所有状态变更
 * 都在该锁内完成。</p>
 */
@Component
public class PoolRegistry {

    private final ConcurrentHashMap<String, Pool> pools = new ConcurrentHashMap<>();

    /**
     * 创建交易对与空池；已存在则抛 PAIR_ALREADY_EXISTS。
     */
    public Pool create(Pair pair) {
        Pool pool = new Pool(pair);
        Pool existing = pools.putIfAbsent(pair.key(), pool);
        if (existing != null) {
            throw new ApiException(ErrorCode.PAIR_ALREADY_EXISTS,
                    "交易对已存在：" + pair.key());
        }
        return pool;
    }

    /**
     * 查找已存在的池，不存在抛 PAIR_NOT_FOUND。
     */
    public Pool require(Pair pair) {
        Pool pool = pools.get(pair.key());
        if (pool == null) {
            throw new ApiException(ErrorCode.PAIR_NOT_FOUND, "交易对不存在：" + pair.key());
        }
        return pool;
    }

    public Pool find(Pair pair) {
        return pools.get(pair.key());
    }

    public List<Pool> all() {
        return pools.values().stream()
                .sorted((a, b) -> a.getPair().key().compareTo(b.getPair().key()))
                .toList();
    }
}
