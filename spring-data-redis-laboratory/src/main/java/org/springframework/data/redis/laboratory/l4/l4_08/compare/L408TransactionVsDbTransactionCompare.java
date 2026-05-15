package org.springframework.data.redis.laboratory.l4.l4_08.compare;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;

import java.util.List;

/**
 * Redis 事务 vs DB 事务对比。
 * <p>
 * 核心差别：
 * <ul>
 *   <li>Redis 事务<b>无业务回滚</b>，命令运行错只影响该条；</li>
 *   <li>DB 事务有 ACID + MVCC + rollback；</li>
 *   <li>Redis 事务不能替代账务系统、不能跨系统强一致。</li>
 * </ul>
 */
public class L408TransactionVsDbTransactionCompare {

    private final StringRedisTemplate template;

    public L408TransactionVsDbTransactionCompare(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 复现"Redis 事务无回滚"：第一条 SET 成功，第二条 LPUSH WRONGTYPE 报错，
     * 第三条 SET 仍然成功——这就是 Redis 与 DB 的本质差。
     */
    public List<Object> redisTransactionNoRollbackExample() {
        String k1 = L408Keys.txLab(3001);
        String k2 = L408Keys.txLab(3002); // 故意做成 String 然后让事务里 LPUSH 它
        String k3 = L408Keys.txLab(3003);

        template.delete(k1);
        template.delete(k3);
        template.opsForValue().set(k2, "this-is-string");

        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForValue().set(k1, "first-success");
            ops.opsForList().leftPush(k2, "boom");          // WRONGTYPE，仅这条报错
            ops.opsForValue().set(k3, "third-still-success");
            return ops.exec();
        });
    }

    /**
     * DB 事务占位：真实业务里这才是事实源。
     */
    public void dbTransactionPlaceholder() {
        System.out.println("=== DB 事务占位 ===");
        System.out.println("// @Transactional");
        System.out.println("// public void doBusiness(...) {");
        System.out.println("//   mapper.update(...);");
        System.out.println("//   if (somethingWrong) throw new RuntimeException();");
        System.out.println("//   // 抛异常时整事务 rollback");
        System.out.println("// }");
        System.out.println("- DB 事务靠 undo log 回滚；");
        System.out.println("- 隔离级别 + MVCC 控制并发可见性；");
        System.out.println("- 跨表跨行强一致；");
        System.out.println("- Redis 事务做不到这些。");
    }

    public void cleanup() {
        template.delete(List.of(L408Keys.txLab(3001), L408Keys.txLab(3002), L408Keys.txLab(3003)));
    }
}
