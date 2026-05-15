package org.springframework.data.redis.laboratory.l4.l4_09.scan;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * L4-09 Cursor 生命周期实验。
 * <p>
 * 一句话：Cursor 不是普通 Iterator，它背后绑定一条 sticky 连接和服务器端游标状态，
 * 必须 close。SDR 选 Cursor 而不是 List/Iterable 就是为了把"远程资源"暴露成"可关闭"。
 * <p>
 * 断点位置：
 * <ul>
 *   <li>{@code Cursor.hasNext} / {@code Cursor.next} —— 触发跨网络的下一轮 SCAN</li>
 *   <li>{@code Cursor.close} —— 释放 sticky 连接</li>
 *   <li>{@code AbstractCursor.close()} —— 看是否幂等、是否归还连接</li>
 *   <li>{@code RedisTemplate.executeWithStickyConnection} —— 看 sticky 连接的获取与归还时机</li>
 * </ul>
 */
public class L409CursorLifecycleLab {

    private final StringRedisTemplate template;

    public L409CursorLifecycleLab(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 正确姿势：try-with-resources。
     * Cursor 实现了 Closeable，try 块退出时（正常 / 异常）都会 close。
     */
    public List<String> correctTryWithResourcesExample(String pattern, int count, int maxKeys) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        List<String> result = new ArrayList<>();
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext() && result.size() < maxKeys) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /**
     * 反例：忘记 close 的连接泄漏。
     * <p>
     * 不要在生产里这样写——sticky 连接不会归还，连接池逐渐耗尽，最终触发"获取连接超时"。
     * 这里方法名带 wrong 是为了对照学习，<b>仅用于断点观察泄漏现象</b>。
     */
    @Deprecated
    public List<String> wrongCursorLeakExample(String pattern, int count, int maxKeys) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        Cursor<String> cursor = template.scan(options); // 没有 try-with-resources
        List<String> result = new ArrayList<>();
        while (cursor.hasNext() && result.size() < maxKeys) {
            result.add(cursor.next());
        }
        // 故意不 close：演示泄漏。请勿照抄到业务代码。
        return result;
    }

    /**
     * 反例：把 Cursor 返回到上层。
     * <p>
     * Controller / Service 拿到一个 Cursor 后，连接生命周期的责任就被甩到了上层——
     * 任何遗漏 close 都是泄漏点。Cursor 必须在 DAO/数据访问层闭合。
     * 这里用 throw 来说明这种 API 设计本身就是反例。
     */
    @Deprecated
    public Cursor<String> wrongReturnCursorExample(String pattern, int count) {
        throw new UnsupportedOperationException("Cursor 必须在 DAO 内部闭合，不能向上层返回——本方法仅作为反例占位。正确姿势：传入 Consumer 在 DAO 内消费完毕，或 DAO 直接返回 List/Set。");
    }

    /**
     * 推荐姿势：传 Consumer 进 DAO，DAO 负责 cursor 生命周期。
     * 上层只关心"对每个 key 做什么"，不关心"什么时候 close 连接"。
     * 这是把"Iterator 反转成 Visitor"，参考 Stream.forEach 的设计哲学。
     */
    public long consumeCursorSafely(String pattern, int count, long maxKeys, Consumer<String> action) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        long processed = 0;
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext() && processed < maxKeys) {
                action.accept(cursor.next());
                processed++;
            }
        }
        return processed;
    }
}
