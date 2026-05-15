package org.springframework.data.redis.laboratory.l4.l4_04.list;

import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.time.Duration;
import java.util.List;

/**
 * "最近浏览的 N 件商品"：典型的 List 三件套 —— LREM 去重 + LPUSH 头插 + LTRIM 控长度。
 * <p>
 * 关键认知：
 * 1) 这三步在 Spring Data Redis 里是三次 RTT，不是原子的。
 * 高并发下同一用户两次浏览同一商品，可能出现重复。要严格原子，用 Lua 把 LREM+LPUSH+LTRIM 包起来（见 L4-10）。
 * 2) 长度上限要严格 trim，否则一个用户能 push 出几十万元素，吃掉 Redis 内存。
 * 3) 每个 user 一个 key，过期时间一般和登录态相当（7~30 天）。
 */
public class L404RecentViewListScenario {

    private static final int MAX_SIZE = 20;
    private static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate template;
    private final ListOperations<String, String> ops;

    public L404RecentViewListScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForList();
    }

    /**
     * 添加一次浏览：去重 → 头插 → 截断 → 续期。
     */
    public void addView(String userId, String itemId) {
        String key = L404Keys.RECENT_VIEW + userId;
        ops.remove(key, 0, itemId);             // LREM count=0：删除全部相等元素
        ops.leftPush(key, itemId);               // LPUSH：最新放最前
        ops.trim(key, 0, MAX_SIZE - 1);          // LTRIM：保留前 MAX_SIZE 条
        template.expire(key, TTL);               // 续期，等价于"用户活跃就续命"
        // 断点提示: DefaultListOperations.remove → connection.listCommands().lRem
        //          DefaultListOperations.leftPush → connection.listCommands().lPush
        //          DefaultListOperations.trim → connection.listCommands().lTrim
    }

    /**
     * 取最近 limit 条。limit > MAX_SIZE 也只能拿到实际存在的。
     */
    public List<String> getRecentViews(String userId, int limit) {
        String key = L404Keys.RECENT_VIEW + userId;
        return ops.range(key, 0, limit - 1);
    }

    public void clearViews(String userId) {
        template.delete(L404Keys.RECENT_VIEW + userId);
    }
}
