package org.springframework.data.redis.laboratory.l4.l4_08.watch;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;

import java.util.Arrays;
import java.util.List;

/**
 * WATCH 基础实验。
 * <p>
 * 重点：
 * <ul>
 *   <li>WATCH 不是锁，不会阻塞别人写；它只在 EXEC 时检测被 watched 的 key 是否被改过；</li>
 *   <li>GET 必须在 MULTI 前；MULTI 后 GET 也只是入队；</li>
 *   <li>EXEC 返回 null/empty 表示冲突，业务必须显式重试或降级；</li>
 *   <li>UNWATCH 清除当前连接上的 watch 标记，不"释放锁"——Redis 没有锁。</li>
 * </ul>
 */
public class L408WatchBasicLab {

    private final StringRedisTemplate template;

    public L408WatchBasicLab(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 单 key WATCH，没有任何并发干扰，EXEC 应当成功。
     */
    public List<Object> watchSingleKeyExample() {
        String k = L408Keys.watchLab(1);
        template.opsForValue().set(k, "v0");

        return L408Transactions.runTxString(template, ops -> {
            ops.watch(k);
            String current = ops.opsForValue().get(k);
            // 业务前置判断：当前值符合预期才进入事务
            if (!"v0".equals(current)) {
                ops.unwatch();
                return List.of();
            }
            ops.multi();
            ops.opsForValue().set(k, "v1-by-tx");
            return ops.exec();
        });
    }

    /**
     * 多 key WATCH。
     * <p>
     * 注意 Cluster 下多 key 必须落同一 slot；本 lab 用相同业务前缀 + 不带 {tag}，
     * 在单机 Redis 上没问题；优惠券场景在 {@link org.springframework.data.redis.laboratory.l4.l4_08.scenario.L408CouponClaimTransactionScenario}
     * 里使用 hash tag。
     */
    public List<Object> watchMultiKeysExample() {
        String k1 = L408Keys.watchLab(2);
        String k2 = L408Keys.watchLab(3);
        template.opsForValue().set(k1, "1");
        template.opsForValue().set(k2, "2");

        return L408Transactions.runTxString(template, ops -> {
            ops.watch(Arrays.asList(k1, k2));
            ops.multi();
            ops.opsForValue().increment(k1);
            ops.opsForValue().increment(k2);
            return ops.exec();
        });
    }

    /**
     * 演示 UNWATCH。
     */
    public void unwatchExample() {
        String k = L408Keys.watchLab(4);
        template.opsForValue().set(k, "v0");

        L408Transactions.runTxString(template, ops -> {
            ops.watch(k);
            // 业务前置校验失败，主动释放 watch
            ops.unwatch();
            return null;
        });
    }

    /**
     * 无并发冲突，EXEC 成功。
     */
    public List<Object> execSuccessWhenNoConflict() {
        String k = L408Keys.watchLab(5);
        template.opsForValue().set(k, "v0");
        return L408Transactions.runTxString(template, ops -> {
            ops.watch(k);
            ops.opsForValue().get(k); // 真正的 GET，在 MULTI 前
            ops.multi();
            ops.opsForValue().set(k, "v1");
            return ops.exec();
        });
    }

    /**
     * 故意制造冲突：在 SessionCallback 内通过散方法调 set，会拿"另一连接"修改 watched key。
     * 由此触发 EXEC 返回 null/empty。
     * <p>
     * 这种"自己干扰自己"的写法只用于本地复现；真实冲突来源是其他客户端。
     */
    public List<Object> execFailWhenWatchedKeyChanged() {
        String k = L408Keys.watchLab(6);
        template.opsForValue().set(k, "v0");

        return L408Transactions.runTxString(template, ops -> {
            ops.watch(k);
            // 用 template 的散调用模拟"另一个客户端"修改 watched key
            template.opsForValue().set(k, "modified-by-other");
            ops.multi();
            ops.opsForValue().set(k, "v1");
            return ops.exec(); // 期望 null / empty
        });
    }

    /**
     * 正确：GET 在 MULTI 之前。
     */
    public String getBeforeMultiCorrectExample() {
        String k = L408Keys.watchLab(7);
        template.opsForValue().set(k, "100");
        return L408Transactions.runTxString(template, ops -> {
            ops.watch(k);
            String value = ops.opsForValue().get(k); // ← 这里能拿到真实值
            ops.multi();
            ops.opsForValue().increment(k);
            ops.exec();
            return value;
        });
    }

    /**
     * 反例：GET 在 MULTI 之后——拿到 null。
     */
    public String getAfterMultiWrongExample() {
        String k = L408Keys.watchLab(8);
        template.opsForValue().set(k, "100");
        return L408Transactions.runTxString(template, ops -> {
            ops.watch(k);
            ops.multi();
            String value = ops.opsForValue().get(k); // ← 入队，立刻返回 null
            ops.exec();
            return value;
        });
    }

    /**
     * 清理 lab key。
     */
    public void cleanupAll() {
        for (int i = 1; i <= 8; i++) {
            template.delete(L408Keys.watchLab(i));
        }
    }
}
