package org.springframework.data.redis.laboratory.l4.l4_08.transaction;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * L4-08 事务基础实验。
 * <p>
 * 每个方法都是一个独立可调用的实验单元，重点关注：
 * <ol>
 *   <li>对应的 Redis 命令；</li>
 *   <li>对应的 Spring Data Redis API；</li>
 *   <li>建议断点位置；</li>
 *   <li>EXEC 返回值顺序；</li>
 *   <li>新手最容易踩的坑。</li>
 * </ol>
 * <p>
 * <b>核心强调</b>：Redis 事务不是数据库事务，没有回滚；MULTI 后命令只入队，
 * 不会立即返回真实业务结果——所有"业务结果"都在 EXEC 返回的 List 里。
 */
public class L408TransactionBasicOperationsLab {

    private final StringRedisTemplate template;

    public L408TransactionBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
    }

    // ========================================================================
    // 一、SessionCallback 流派 —— 业务首选
    // ========================================================================

    /**
     * MULTI/EXEC 最小例子：连续 SET 两个 key。
     * <ul>
     *   <li>命令：MULTI / SET k1 v1 / SET k2 v2 / EXEC</li>
     *   <li>API：RedisOperations#multi/exec</li>
     *   <li>断点：RedisTemplate#execute(SessionCallback)、RedisOperations#multi、
     *           RedisConnection#multi、DefaultValueOperations#set、RedisOperations#exec、
     *           RedisTemplate#execRaw、RedisTemplate#deserializeMixedResults</li>
     * </ul>
     * EXEC 返回 [OK, OK]（已被 Spring 反序列化为 {@code Boolean.TRUE} 或类似）。
     */
    public List<Object> multiExecBasicExample() {
        String k1 = L408Keys.txLab(1);
        String k2 = L408Keys.txLab(2);
        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForValue().set(k1, "v1");
            ops.opsForValue().set(k2, "v2");
            return ops.exec();
        });
    }

    /**
     * MULTI 后调用 DISCARD：所有入队命令被丢弃，EXEC 不应再被调用。
     * <p>
     * 业务侧场景：进入 MULTI 后才发现需要中止（少见，更多是前置校验）。
     * 这里 demo 调用顺序是 multi → set → discard，不再 exec。
     */
    public void multiDiscardExample() {
        String k = L408Keys.txLab(3);
        L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForValue().set(k, "should-be-discarded");
            ops.discard();
            // discard 后再 exec 会抛错；业务不要这样写。
            return null;
        });
    }

    /**
     * 演示 EXEC 返回 mixed results：String/Long/Boolean 混在一起。
     * <ul>
     *   <li>SET (String OK)</li>
     *   <li>INCR (Long)</li>
     *   <li>EXPIRE (Boolean)</li>
     *   <li>GET (String)</li>
     * </ul>
     * 顺序与入队顺序严格一致。新手禁止盲目强转。
     */
    public List<Object> transactionMixedResultsExample() {
        String k = L408Keys.txLab(4);
        // 先清掉残留，避免 INCR 在非数字字符串上抛 WRONGNUM
        template.delete(k);
        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForValue().set(k, "0");
            ops.opsForValue().increment(k);
            ops.expire(k, Duration.ofMinutes(5));
            ops.opsForValue().get(k);
            return ops.exec();
        });
    }

    /**
     * 关键反直觉点：事务内某条命令运行错误，<b>不会回滚</b>其他命令。
     * <p>
     * 复现：先把 key 设为 String，事务内对它执行 LPUSH（WRONGTYPE）。
     * EXEC 返回的 List 中，LPUSH 那条会是异常对象 / 报错；前面的 SET 已经成功落库。
     * <p>
     * 业务侧记住：靠 Redis 事务回滚是幻觉，类型必须前置校验或用 Lua。
     */
    public List<Object> transactionRuntimeErrorNoRollbackExample() {
        String stringKey = L408Keys.txLab(5);
        template.delete(stringKey);

        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForValue().set(stringKey, "this-is-string");
            // 对一个 String key 执行 LPUSH —— 必定 WRONGTYPE
            ops.opsForList().leftPush(stringKey, "boom");
            ops.opsForValue().set(L408Keys.txLab(6), "still-success");
            return ops.exec();
        });
    }

    /**
     * 演示 MULTI 后命令只入队，立即返回值不能反映真实业务结果。
     * <p>
     * 调用 {@code ops.opsForValue().get(...)} 此时返回 null；真正结果要等 EXEC。
     */
    public List<Object> transactionQueueingBehaviorExample() {
        String k = L408Keys.txLab(7);
        template.opsForValue().set(k, "real-value");
        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            String inQueue = ops.opsForValue().get(k);
            // 事务内打印的将是 null，因为命令尚未执行
            System.out.println("[queueing] inQueue GET return = " + inQueue);
            return ops.exec();
        });
    }

    /**
     * 事务 + ValueOperations。
     */
    public List<Object> transactionWithStringOperations() {
        String k = L408Keys.txLab(8);
        template.delete(k);
        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForValue().set(k, "init");
            ops.opsForValue().append(k, "-suffix");
            ops.opsForValue().get(k);
            return ops.exec();
        });
    }

    /**
     * 事务 + HashOperations。
     */
    public List<Object> transactionWithHashOperations() {
        String k = L408Keys.txLab(9);
        template.delete(k);
        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForHash().put(k, "uid", "1001");
            ops.opsForHash().put(k, "name", "leiyuhang");
            ops.opsForHash().increment(k, "score", 10);
            ops.opsForHash().entries(k);
            return ops.exec();
        });
    }

    /**
     * 事务 + ZSetOperations。
     */
    public List<Object> transactionWithZSetOperations() {
        String k = L408Keys.txLab(10);
        template.delete(k);
        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForZSet().add(k, "u1", 100);
            ops.opsForZSet().add(k, "u2", 200);
            ops.opsForZSet().incrementScore(k, "u1", 50);
            ops.opsForZSet().reverseRange(k, 0, -1);
            return ops.exec();
        });
    }

    // ========================================================================
    // 二、RedisCallback 流派 —— 调试源码首选
    // ========================================================================

    /**
     * 用 RedisCallback 直接对 RedisConnection 发命令做事务。
     * <ul>
     *   <li>断点：LettuceConnection#multi、LettuceStringCommands#set、LettuceConnection#exec</li>
     *   <li>从这里能"看见"事务命令真正落到哪个 native 连接上</li>
     * </ul>
     */
    public List<Object> redisCallbackTransactionExample() {
        @SuppressWarnings("unchecked")
        RedisSerializer<String> keySer = (RedisSerializer<String>) template.getKeySerializer();
        byte[] k1 = keySer.serialize(L408Keys.txLab(11));
        byte[] k2 = keySer.serialize(L408Keys.txLab(12));

        return template.execute((RedisCallback<List<Object>>) connection -> {
            connection.multi();
            connection.stringCommands().set(k1, "v1".getBytes(StandardCharsets.UTF_8));
            connection.stringCommands().set(k2, "v2".getBytes(StandardCharsets.UTF_8));
            // exec 返回的列表是 raw byte[] 级，未经 RedisTemplate 的 deserializeMixedResults
            return connection.exec();
        });
    }

    /**
     * 用 SessionCallback 流派，与上一个方法形成对比。
     */
    public List<Object> sessionCallbackTransactionExample() {
        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForValue().set(L408Keys.txLab(13), "session-v1");
            ops.opsForValue().set(L408Keys.txLab(14), "session-v2");
            return ops.exec();
        });
    }

    // ========================================================================
    // 三、辅助
    // ========================================================================

    /**
     * 清理本 lab 实验残留 key。
     */
    public void cleanupAll() {
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            keys.add(L408Keys.txLab(i));
        }
        template.delete(keys);
    }

    /**
     * 安全打印 mixed results，避免 byte[]/异常对象等炸屏。
     */
    public static String previewResults(List<Object> results) {
        if (results == null) {
            return "null (EXEC aborted: WATCH conflict / discard)";
        }
        if (results.isEmpty()) {
            return "empty (likely conflict)";
        }
        return Arrays.toString(results.toArray());
    }

    public static StringRedisSerializer stringSerializer() {
        return StringRedisSerializer.UTF_8;
    }

    /**
     * 在仅有 RedisConnection 的回调内手动序列化 key（演示用）。
     */
    public static byte[] keyBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[][] keysToBytes(String... keys) {
        byte[][] bs = new byte[keys.length][];
        for (int i = 0; i < keys.length; i++) {
            bs[i] = keyBytes(keys[i]);
        }
        return bs;
    }

    /**
     * 直接通过 RedisConnection 调 watch，不走 ops，便于断点 LettuceConnection#watch。
     */
    public void rawConnectionWatchExample(String key) {
        template.execute((RedisCallback<Object>) (RedisConnection connection) -> {
            connection.watch(keyBytes(key));
            connection.unwatch();
            return null;
        });
    }
}
