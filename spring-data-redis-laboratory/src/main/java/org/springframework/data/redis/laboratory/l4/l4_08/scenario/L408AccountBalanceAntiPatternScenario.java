package org.springframework.data.redis.laboratory.l4.l4_08.scenario;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;

import java.util.List;

/**
 * 账户余额扣减 —— <b>反例</b>。
 * <p>
 * <b>不要</b>把"用户账户余额"这种钱相关账务的最终一致依赖在 Redis 事务上：
 * <ul>
 *   <li>Redis 事务无业务回滚，部分命令失败时账目错乱不可逆；</li>
 *   <li>Redis 持久化不是强一致，AOF 默认 1s fsync，主从切换可能丢失最近写；</li>
 *   <li>账目必须可审计、可对账、可回溯——这是 DB / 账务系统的职责。</li>
 * </ul>
 * <p>
 * 正解：DB 事务（核心）+ 幂等键 + 异步对账 + 必要时 MQ 解耦。
 * Redis 在账务体系里只能做"展示加速"或"前置防刷"，不是事实源。
 * <p>
 * 这个类只演示"如果你这样写，会有什么风险"，不要照搬到生产。
 */
public class L408AccountBalanceAntiPatternScenario {

    private final StringRedisTemplate template;

    public L408AccountBalanceAntiPatternScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 反例：直接用 WATCH + DECRBY 做余额扣减。
     * <p>
     * 表面看起来"乐观锁 + 原子扣减"，但：
     * <ul>
     *   <li>Redis 节点崩溃 / 主从切换可能丢失最后一次扣减——账户里多了钱，企业亏本；</li>
     *   <li>没有审计日志、没有对账依据；</li>
     *   <li>跨系统场景（DB 同步入账失败）无法自动回滚 Redis 端余额。</li>
     * </ul>
     */
    public List<Object> unsafeRedisBalanceDeductExample(String userId, long amount) {
        String balKey = L408Keys.balanceUser(userId);

        return L408Transactions.runTxString(template, ops -> {
            ops.watch(balKey);
            String raw = ops.opsForValue().get(balKey);
            long bal = raw == null ? 0L : Long.parseLong(raw);
            if (bal < amount) {
                ops.unwatch();
                return List.of();
            }
            ops.multi();
            ops.opsForValue().increment(balKey, -amount);
            return ops.exec();
        });
    }

    public void explainWhyNotRecommended() {
        System.out.println("=== Redis 事务做账户余额：风险清单 ===");
        System.out.println("1. 无业务回滚：第二步 DB 入账失败，第一步 Redis 扣减无法自动撤销；");
        System.out.println("2. 持久化弱：AOF 1s fsync 默认丢 1 秒；主从切换可能丢更多；");
        System.out.println("3. 无审计：没有交易流水，无法对账；");
        System.out.println("4. 一致性弱：跨用户账务（A→B 转账）需要原子，Redis 跨 key 事务在 Cluster 还要 hash tag；");
        System.out.println("5. 监管要求：金融业务通常要求强一致 + 可审计，Redis 不达标。");
        System.out.println("正解：DB 事务为事实源；Redis 仅做展示加速 / 前置防刷。");
    }

    /**
     * 占位说明：真实业务里这里应当走 DataSourceTransactionManager + @Transactional。
     */
    public void dbTransactionPlaceholder() {
        System.out.println("=== DB 事务占位 ===");
        System.out.println("// @Transactional");
        System.out.println("// public void deduct(...) {");
        System.out.println("//   accountMapper.lockForUpdate(userId);");
        System.out.println("//   accountMapper.deduct(userId, amount);");
        System.out.println("//   journalMapper.insertJournal(...);  // 流水必须落库");
        System.out.println("// }");
    }

    public void clearData(String userId) {
        template.delete(L408Keys.balanceUser(userId));
    }

    public void initBalance(String userId, long amount) {
        template.opsForValue().set(L408Keys.balanceUser(userId), String.valueOf(amount));
    }

    public Long getBalance(String userId) {
        String raw = template.opsForValue().get(L408Keys.balanceUser(userId));
        return raw == null ? null : Long.parseLong(raw);
    }
}
