package org.springframework.data.redis.laboratory.l4.l4_03.string;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

/**
 * 计数器场景：商品浏览数 / 点赞数 / 收藏数 / 限流计数。
 * <p>
 * 关键：永远用 INCR/DECR，永远不要"GET → 改 → SET"。
 * Redis 单线程串行执行命令，INCR 在服务端原子完成；
 * 而 GET+SET 在客户端是两次独立 RTT，并发下后写覆盖前写，丢数。
 */
public class L403CounterScenario {

    private final ValueOperations<String, String> ops;
    private final StringRedisTemplate template;

    public L403CounterScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    private static String viewKey(String itemId) {
        return L403Keys.COUNTER + "view:" + itemId;
    }

    private static String likeKey(String itemId) {
        return L403Keys.COUNTER + "like:" + itemId;
    }

    public Long increaseViewCount(String itemId) {
        return ops.increment(viewKey(itemId));
    }

    public Long decreaseLikeCount(String itemId) {
        return ops.decrement(likeKey(itemId));
    }

    public Long getCount(String key) {
        String v = ops.get(key);
        return v == null ? 0L : Long.parseLong(v);
    }

    /**
     * 反例：高并发下会丢数。
     * 两个线程同时 GET 拿到 100，各自 +1 后 SET 101，最终结果 101 而不是 102。
     * 仅用作教学，不要在业务代码里这么写。
     */
    public void unsafeGetThenSetCounterExample(String key) {
        String v = ops.get(key);
        long current = v == null ? 0L : Long.parseLong(v);
        ops.set(key, String.valueOf(current + 1));
    }

    /**
     * 正解：服务端原子自增，无论多少并发都不会丢。
     */
    public Long safeIncrementCounterExample(String key) {
        return ops.increment(key);
    }
}
