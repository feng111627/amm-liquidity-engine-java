package com.github.feng111627.ammliquidityenginejava.pool;

import com.github.feng111627.ammliquidityenginejava.error.ApiException;
import com.github.feng111627.ammliquidityenginejava.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等结果存储。
 *
 * <p>以客户端提供的 requestId 为键：</p>
 * <ol>
 *   <li>首次请求：占用 requestId（记录请求指纹），业务执行后写入结果；</li>
 *   <li>同 requestId 重试且指纹相同：直接回放首次结果，标记 replayed=true，不重复扣款；</li>
 *   <li>同 requestId 但指纹不同（业务参数被篡改）：拒绝，IDEMPOTENCY_KEY_CONFLICT。</li>
 * </ol>
 *
 * <p>每个 requestId 自带一把锁（{@link #guardOf(String)}），保证“检查-占用-执行”
 * 原子化；业务执行时再获取池锁，锁顺序固定为 id锁 -> 池锁，避免死锁。</p>
 */
@Component
public class IdempotencyStore {

    public record Entry(String payloadKey, Object result) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    /** 分段锁：即使 requestId 还未登记，同键并发也能互斥。 */
    private final ConcurrentHashMap<String, Object> guards = new ConcurrentHashMap<>();

    /**
     * 返回幂等条目；未出现过该 requestId 返回 empty。
     */
    public Optional<Entry> find(String requestId) {
        return Optional.ofNullable(entries.get(requestId));
    }

    /**
     * 回放或校验已存在的条目；指纹不一致直接抛错。
     */
    public Entry replayOrReject(String requestId, String payloadKey) {
        Entry entry = entries.get(requestId);
        if (!entry.payloadKey().equals(payloadKey)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "requestId=" + requestId + " 已用于另一组请求参数，不能复用");
        }
        return entry;
    }

    /**
     * 首次请求预占（记录指纹），业务完成后由 {@link #complete} 写入结果。
     */
    public void occupy(String requestId, String payloadKey) {
        entries.put(requestId, new Entry(payloadKey, null));
    }

    /**
     * 写入首次执行结果。
     */
    public void complete(String requestId, Object result) {
        Entry entry = entries.get(requestId);
        if (entry != null) {
            entries.put(requestId, new Entry(entry.payloadKey(), result));
        }
    }

    /**
     * 释放失败的预占（业务抛错，允许同键同参重试）。
     */
    public void release(String requestId) {
        entries.remove(requestId);
    }

    public Object guardOf(String requestId) {
        return guards.computeIfAbsent(requestId, k -> new Object());
    }
}
