package org.springframework.data.redis.laboratory.l4.l4_07.pipeline;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.function.Function;

/**
 * SessionCallback 包装器：把"必须用匿名内部类"这件事封装成一个 lambda 友好的工具方法。
 * <p>
 * <b>为什么不能直接写 lambda？</b>
 * <p>
 * {@link SessionCallback#execute(RedisOperations)} 是一个<u>泛型方法</u>
 * （{@code <K,V> T execute(RedisOperations<K,V>)}），Java 规范规定 lambda 表达式
 * 不能实现"泛型方法"——只能实现签名固定的 functional interface。
 * 所以业务代码里直接 {@code (SessionCallback<Object>) ops -> {...}} 编译失败。
 * <p>
 * 这个工具类把"匿名内部类的样板代码"集中收口，业务侧只用：
 * <pre>
 *   L407Pipelines.run(template, ops -> ops.opsForValue().get("k"));
 * </pre>
 * <p>
 * 这本身就是 Pipeline 偷师章节的具体落地：把 SDK 接口形态封装成业务友好的 API。
 */
public final class L407Pipelines {

    private L407Pipelines() {
    }

    /**
     * String key/value 场景：执行 Pipeline 并返回 mixed results。
     * <p>
     * 业务侧 lambda 必须 {@code return null}（这是 SessionCallback 的契约——Pipeline 模式下
     * 真实结果在 closePipeline 时一次性吐回，回调里不要返回业务对象）。
     * 用 {@code Function<..., Object>} 而非 {@code Consumer} 就是为了让业务"显式 return null"，
     * 提醒它"我没忘记 SessionCallback 的契约"。
     */
    public static List<Object> run(StringRedisTemplate template,
                                   Function<RedisOperations<String, String>, Object> action) {
        return template.executePipelined(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                return action.apply((RedisOperations<String, String>) operations);
            }
        });
    }
}
