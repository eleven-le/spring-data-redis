package org.springframework.data.redis.laboratory.l4.l4_08.transaction;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;

import java.time.Duration;
import java.util.List;

/**
 * EXEC mixed results 解析专题。
 * <p>
 * 这个 lab 不是讲业务，而是讲"业务该怎么读 EXEC 返回值"：
 * <ul>
 *   <li>顺序：返回 List 的索引严格对应入队顺序；</li>
 *   <li>类型：每条命令返回类型不同，业务必须知道"我入队了哪些命令"；</li>
 *   <li>冲突信号：EXEC 返回 null / empty 表示 WATCH 冲突或 DISCARD；</li>
 *   <li>序列化：换 valueSerializer 会让结果对象类型变化（同一条命令，String 和 JSON 序列化器返回的值类型完全不同）。</li>
 * </ul>
 */
public class L408TransactionResultMappingLab {

    private final StringRedisTemplate stringTemplate;
    private final RedisTemplate<String, Object> objectTemplate;

    public L408TransactionResultMappingLab(StringRedisTemplate stringTemplate,
                                           RedisTemplate<String, Object> objectTemplate) {
        this.stringTemplate = stringTemplate;
        this.objectTemplate = objectTemplate;
    }

    /**
     * 演示：返回顺序 == 入队顺序。
     * <p>
     * 入队 5 条命令，业务侧拿到的 List 索引 0~4 一一对应。
     */
    public List<Object> mapExecResultsByCommandOrder() {
        String a = L408Keys.txResultMap(1);
        String b = L408Keys.txResultMap(2);
        String c = L408Keys.txResultMap(3);
        stringTemplate.delete(List.of(a, b, c));

        return L408Transactions.runTxString(stringTemplate, ops -> {
            ops.multi();
            ops.opsForValue().set(a, "1");        // [0] -> OK / Boolean
            ops.opsForValue().increment(b);       // [1] -> Long(1)
            ops.opsForValue().set(c, "x");        // [2] -> OK / Boolean
            ops.expire(c, Duration.ofMinutes(1)); // [3] -> Boolean
            ops.opsForValue().get(c);             // [4] -> "x"
            return ops.exec();
        });
    }

    /**
     * 演示：mixed results 中常见的几种 Java 类型，用注释逐条标注。
     */
    public List<Object> demonstrateMixedResultTypes() {
        String hashKey = L408Keys.txResultMap(4);
        String zKey = L408Keys.txResultMap(5);
        String setKey = L408Keys.txResultMap(6);
        stringTemplate.delete(List.of(hashKey, zKey, setKey));

        return L408Transactions.runTxString(stringTemplate, ops -> {
            ops.multi();
            ops.opsForHash().put(hashKey, "f1", "v1");      // Boolean / OK
            ops.opsForHash().increment(hashKey, "score", 5); // Long
            ops.opsForZSet().add(zKey, "u1", 100);           // Boolean
            ops.opsForZSet().score(zKey, "u1");              // Double
            ops.opsForSet().add(setKey, "x", "y");           // Long(添加成员数)
            ops.opsForHash().entries(hashKey);               // Map
            return ops.exec();
        });
    }

    /**
     * 反例：常见的"瞎强转"踩坑。
     * <p>
     * 这里不真的崩溃，只演示 <b>错误的</b> 取值方式。
     * 业务代码千万不要这样写——一旦命令顺序变更或某条 API 内部行为升级，立刻 ClassCastException。
     */
    public void demonstrateWrongCastPitfall(List<Object> execResult) {
        if (execResult == null || execResult.size() < 2) {
            System.out.println("[wrong-cast-demo] result aborted, skip.");
            return;
        }
        try {
            // 假设事务内第二条命令本来是 INCR（返回 Long），有人却把它当 String 取
            String wrong = (String) execResult.get(1);
            System.out.println("[wrong-cast-demo] succeeded by luck = " + wrong);
        } catch (ClassCastException e) {
            System.out.println("[wrong-cast-demo] caught CCE: " + e.getMessage()
                    + " —— 这就是为什么业务必须封装 ResultMapper");
        }
    }

    /**
     * 演示：EXEC 在 WATCH 冲突 / DISCARD 时返回 null（或 empty）。
     * <p>
     * 这里我们故意"自己监视自己改"：先 WATCH 再在事务外修改 watched key。
     * 真实业务里冲突来源是其他客户端，用 {@code L408WatchConflictSimulationScenario} 实验。
     */
    public List<Object> demonstrateExecNullConflictResult() {
        String k = L408Keys.txResultMap(7);
        stringTemplate.opsForValue().set(k, "v0");

        return L408Transactions.runTxString(stringTemplate, ops -> {
            ops.watch(k);
            // 在 MULTI 之前用<b>另一个连接</b>修改它：
            // 简单起见，借助同一 template 但 unbind 后再写——这里直接调用 template.opsForValue().set()
            // 在 SessionCallback 内 redirectally 调用同一 template 的散方法，会拿"另一个"连接。
            stringTemplate.opsForValue().set(k, "modified-by-other");
            ops.multi();
            ops.opsForValue().set(k, "tx-want-set");
            return ops.exec(); // 期望：null / empty
        });
    }

    /**
     * 演示：valueSerializer 不同导致结果类型不同。
     * <p>
     * 同样是 {@code opsForValue().get(k)}：
     * <ul>
     *   <li>StringRedisTemplate 拿到 String；</li>
     *   <li>RedisTemplate&lt;String, Object&gt; 配 Jackson 拿到反序列化后的 POJO 或原始 String。</li>
     * </ul>
     */
    public void explainSerializerEffect() {
        String k = L408Keys.txResultMap(8);
        // 用 ObjectTemplate 写一个 POJO（这里就用 String 演示，重点是序列化器形态）
        objectTemplate.opsForValue().set(k, "json-encoded-string");

        List<Object> r1 = L408Transactions.runTxString(stringTemplate, ops -> {
            ops.multi();
            ops.opsForValue().get(k);
            return ops.exec();
        });
        List<Object> r2 = L408Transactions.runTx(objectTemplate, ops -> {
            ops.multi();
            ops.opsForValue().get(k);
            return ops.exec();
        });
        System.out.println("[serializer-effect] StringTemplate result class = "
                + (r1 == null || r1.isEmpty() ? "null" : r1.get(0).getClass().getSimpleName())
                + ", value = " + r1);
        System.out.println("[serializer-effect] ObjectTemplate result class = "
                + (r2 == null || r2.isEmpty() ? "null" : r2.get(0).getClass().getSimpleName())
                + ", value = " + r2);
    }

    /**
     * 清理本 lab 实验残留 key。
     */
    public void cleanupAll() {
        for (int i = 1; i <= 8; i++) {
            stringTemplate.delete(L408Keys.txResultMap(i));
        }
    }
}
