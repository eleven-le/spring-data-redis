package org.springframework.data.redis.laboratory.l4.l4_08.compare;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;

import java.util.List;

/**
 * WATCH（乐观锁） vs 分布式锁（悲观锁）对比。
 * <p>
 * <ul>
 *   <li>WATCH：不阻塞别人，仅在提交时检测冲突 → 失败重试；</li>
 *   <li>分布式锁：互斥，只有一个客户端能进入临界区 → 别人等待 / 失败；</li>
 *   <li>低冲突 → 乐观；高冲突 → 悲观更稳，但有锁过期 / 续期 / 死锁等复杂度。</li>
 * </ul>
 */
public class L408WatchVsDistributedLockCompare {

    private final StringRedisTemplate template;

    public L408WatchVsDistributedLockCompare(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * WATCH 乐观冲突检测：标准模板。
     */
    public List<Object> watchOptimisticConflictDetection() {
        String k = L408Keys.txLab(4001);
        template.opsForValue().set(k, "0");
        return L408Transactions.runTxString(template, ops -> {
            ops.watch(k);
            String raw = ops.opsForValue().get(k);
            long current = raw == null ? 0L : Long.parseLong(raw);
            ops.multi();
            ops.opsForValue().set(k, String.valueOf(current + 1));
            return ops.exec();
        });
    }

    /**
     * 分布式锁占位：真实业务推荐 Redisson / Lua + EXAT + 续期。
     */
    public void lockMutualExclusionPlaceholder() {
        System.out.println("=== 分布式锁占位 ===");
        System.out.println("// boolean ok = redisson.getLock(key).tryLock(5, 30, SECONDS);");
        System.out.println("// if (!ok) throw ...;");
        System.out.println("// try { 业务... } finally { lock.unlock(); }");
        System.out.println("- 互斥：临界区一次只允许一个客户端；");
        System.out.println("- 复杂度高：锁过期、续期、可重入、看门狗、宕机；");
        System.out.println("- 高并发互斥本身是性能瓶颈，配合 Lua 把临界区放进 server 侧最佳。");
    }

    public void cleanup() {
        template.delete(L408Keys.txLab(4001));
    }
}
