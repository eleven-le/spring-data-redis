package org.springframework.data.redis.laboratory.l4.l4_08.toushi;

/**
 * 偷师：原子方案选择 → 策略模式抽象。
 * <p>
 * 业务里"扣库存 / 扣积分 / 跨账户转账"这类原子需求，可选方案常常是：
 * <ul>
 *   <li>{@link WatchTransactionStrategy} —— Redis WATCH + MULTI/EXEC，乐观锁</li>
 *   <li>{@link LuaScriptStrategy} —— Redis Lua 脚本，server 侧原子</li>
 *   <li>{@link DbTransactionStrategy} —— DB 事务，事实源强一致</li>
 *   <li>{@link MqSerialStrategy} —— MQ 串行化消费，跨系统最终一致</li>
 * </ul>
 * <p>
 * 把它们抽成 {@link AtomicStrategy} 接口，业务侧根据"冲突率 / 一致性强度 / 跨系统"切换实现。
 * 这样灰度新方案、A/B 压测、回滚老方案都只是换实现而非改业务。
 */
public class AtomicStrategyDesignDemo {

    public interface AtomicStrategy {
        boolean tryDeduct(String resource, long quantity);
        String name();
    }

    public static class WatchTransactionStrategy implements AtomicStrategy {
        @Override public boolean tryDeduct(String resource, long quantity) {
            System.out.println("  [watch-tx] deduct " + resource + " by " + quantity
                    + " —— 适合低/中冲突乐观更新");
            return true;
        }
        @Override public String name() { return "WATCH+MULTI/EXEC"; }
    }

    public static class LuaScriptStrategy implements AtomicStrategy {
        @Override public boolean tryDeduct(String resource, long quantity) {
            System.out.println("  [lua] eval-script for " + resource + " by " + quantity
                    + " —— 适合 Redis 内部短小读判断写、高并发");
            return true;
        }
        @Override public String name() { return "Lua"; }
    }

    public static class DbTransactionStrategy implements AtomicStrategy {
        @Override public boolean tryDeduct(String resource, long quantity) {
            System.out.println("  [db-tx] @Transactional update " + resource + " by " + quantity
                    + " —— 适合事实源强一致 / 财务账务");
            return true;
        }
        @Override public String name() { return "DB Transaction"; }
    }

    public static class MqSerialStrategy implements AtomicStrategy {
        @Override public boolean tryDeduct(String resource, long quantity) {
            System.out.println("  [mq] enqueue deduct(" + resource + ", " + quantity + ")"
                    + " —— 适合削峰 / 跨系统最终一致");
            return true;
        }
        @Override public String name() { return "MQ Serial"; }
    }

    /** 业务编排：根据冲突率 / 一致性需求选策略。 */
    public static final class AtomicOperationService {
        private AtomicStrategy strategy;
        public AtomicOperationService(AtomicStrategy initial) { this.strategy = initial; }
        public void switchStrategy(AtomicStrategy s) {
            System.out.println("[strategy-switch] " + strategy.name() + " -> " + s.name());
            this.strategy = s;
        }
        public boolean deduct(String resource, long quantity) {
            System.out.println("[do] strategy=" + strategy.name());
            return strategy.tryDeduct(resource, quantity);
        }
    }

    public static void main(String[] args) {
        AtomicOperationService svc = new AtomicOperationService(new WatchTransactionStrategy());
        svc.deduct("points:user:1001", 100);

        // 高并发压测后切到 Lua
        svc.switchStrategy(new LuaScriptStrategy());
        svc.deduct("stock:sku:s-100", 1);

        // 涉及账务，切到 DB
        svc.switchStrategy(new DbTransactionStrategy());
        svc.deduct("balance:user:1001", 10);

        // 跨系统对账场景
        svc.switchStrategy(new MqSerialStrategy());
        svc.deduct("order:cross-sys:o-1", 1);
    }
}
