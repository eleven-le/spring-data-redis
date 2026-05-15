package org.springframework.data.redis.laboratory.l4.l4_08.transaction;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.function.Function;

/**
 * SessionCallback 包装工具。
 * <p>
 * 设计目的：{@link SessionCallback#execute(RedisOperations)} 是泛型方法，
 * 直接写 lambda 会被 IDE 推断为 raw type，不优雅。这里提供两个工具方法：
 * <ul>
 *   <li>{@link #runTx(RedisTemplate, Function)} —— 在同一连接会话内执行业务逻辑，
 *       返回业务自定义结果（典型为 {@code List<Object>} EXEC 结果）。</li>
 *   <li>{@link #runTxString(StringRedisTemplate, Function)} —— StringRedisTemplate 专用便捷重载。</li>
 * </ul>
 * <p>
 * 这就是 RedisTemplate 内部"Template + Callback"思想的最小化复刻。
 */
public final class L408Transactions {

    private L408Transactions() {
    }

    public static <K, V, R> R runTx(RedisTemplate<K, V> template,
                                    Function<RedisOperations<K, V>, R> action) {
        return template.execute(new SessionCallback<R>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K1, V1> R execute(RedisOperations<K1, V1> operations) {
                return action.apply((RedisOperations<K, V>) operations);
            }
        });
    }

    public static <R> R runTxString(StringRedisTemplate template,
                                    Function<RedisOperations<String, String>, R> action) {
        return template.execute(new SessionCallback<R>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K1, V1> R execute(RedisOperations<K1, V1> operations) {
                return action.apply((RedisOperations<String, String>) operations);
            }
        });
    }

    /**
     * 是否表示 EXEC 因 WATCH 冲突或 DISCARD 被取消。
     * Spring Data Redis 在两种实现上行为略不同：可能为 null，也可能为空 List。
     */
    public static boolean isExecAborted(List<Object> execResult) {
        return execResult == null || execResult.isEmpty();
    }
}
