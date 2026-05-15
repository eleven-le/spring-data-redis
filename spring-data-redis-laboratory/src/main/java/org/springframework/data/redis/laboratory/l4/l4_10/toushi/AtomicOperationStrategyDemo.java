package org.springframework.data.redis.laboratory.l4.l4_10.toushi;

import java.util.HashMap;
import java.util.Map;

/**
 * 偷师 ③：原子操作策略（Strategy）。
 * <p>
 * 同一个业务诉求"原子地完成扣减"，可以有不同实现：
 * <ul>
 *   <li><b>LuaAtomicStrategy</b>：Redis Lua，毫秒级，适合高并发热点</li>
 *   <li><b>DbOptimisticLockStrategy</b>：DB 行级乐观锁（version + WHERE），适合事实源更新</li>
 *   <li><b>MqSerialConsumeStrategy</b>：单 partition 串行消费，适合最终一致 + 削峰</li>
 *   <li><b>DistributedLockStrategy</b>：分布式锁串行执行，适合互斥但不是高并发首选</li>
 * </ul>
 * 业务代码持有 {@code AtomicOperationStrategy} 接口，按场景注入不同实现，避免硬编码。
 */
public class AtomicOperationStrategyDemo {

    public interface AtomicOperationStrategy {
        boolean tryDeduct(String resourceId, long quantity);
    }

    public static class LuaAtomicStrategy implements AtomicOperationStrategy {
        @Override public boolean tryDeduct(String resourceId, long quantity) {
            // 真实实现：redisTemplate.execute(stockDeductScript, keys, qty)
            return true;
        }
    }

    public static class DbOptimisticLockStrategy implements AtomicOperationStrategy {
        @Override public boolean tryDeduct(String resourceId, long quantity) {
            // UPDATE stock SET v=v-?, version=version+1 WHERE id=? AND v>=? AND version=?
            return true;
        }
    }

    public static class MqSerialConsumeStrategy implements AtomicOperationStrategy {
        @Override public boolean tryDeduct(String resourceId, long quantity) {
            // 把扣减消息扔到固定 partition，保证串行消费
            return true;
        }
    }

    public static class DistributedLockStrategy implements AtomicOperationStrategy {
        @Override public boolean tryDeduct(String resourceId, long quantity) {
            // tryLock(resourceId) -> deduct -> release
            return true;
        }
    }

    /** 业务门面，按场景挑策略。 */
    public static class AtomicOperationService {
        private final Map<String, AtomicOperationStrategy> strategies = new HashMap<>();

        public AtomicOperationService register(String name, AtomicOperationStrategy s) {
            strategies.put(name, s); return this;
        }
        public boolean deduct(String name, String resourceId, long qty) {
            return strategies.getOrDefault(name, (id, q) -> false).tryDeduct(resourceId, qty);
        }
    }

    public static void main(String[] args) {
        AtomicOperationService svc = new AtomicOperationService()
                .register("lua", new LuaAtomicStrategy())
                .register("db", new DbOptimisticLockStrategy())
                .register("mq", new MqSerialConsumeStrategy())
                .register("lock", new DistributedLockStrategy());

        System.out.println("hot path -> " + svc.deduct("lua", "sku:1", 1));
        System.out.println("source-of-truth -> " + svc.deduct("db", "sku:1", 1));
    }
}
