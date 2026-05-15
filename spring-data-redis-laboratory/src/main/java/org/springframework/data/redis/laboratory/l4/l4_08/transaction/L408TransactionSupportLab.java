package org.springframework.data.redis.laboratory.l4.l4_08.transaction;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;

/**
 * {@code setEnableTransactionSupport(true)} 的概念实验。
 * <p>
 * <b>它真正的作用：</b>把对 RedisTemplate 的调用纳入到 Spring 的
 * {@code TransactionSynchronizationManager} 同步周期。当外层有 Spring 声明式事务
 * （例如 @Transactional + DataSourceTransactionManager）时，RedisTemplate 会：
 * <ul>
 *   <li>在 Spring 事务开始时把连接绑定到当前线程；</li>
 *   <li>把 multi/exec 与 Spring 事务的 commit/rollback 同步；</li>
 *   <li>在没有外层事务时，行为退化到普通模式。</li>
 * </ul>
 * <p>
 * <b>它不是什么：</b>
 * <ul>
 *   <li>不是"打开后所有 RedisTemplate 操作都自动包成 Redis 事务"；</li>
 *   <li>不能替代 SessionCallback；本章主线就是显式 SessionCallback；</li>
 *   <li>没有外层 PlatformTransactionManager 时基本是空操作。</li>
 * </ul>
 * <p>
 * 这个 lab 只在概念层面演示，不写复杂的混合 DB 事务示例。
 */
public class L408TransactionSupportLab {

    private final LettuceConnectionFactory connectionFactory;

    public L408TransactionSupportLab(LettuceConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * 用文字 + 代码注释解释概念。
     */
    public void explainTransactionSupportConcept() {
        System.out.println("=== setEnableTransactionSupport(true) 概念 ===");
        System.out.println("作用：让 RedisTemplate 调用参与 Spring 声明式事务的同步周期。");
        System.out.println("依赖：外层有 PlatformTransactionManager（如 DataSourceTransactionManager + @Transactional）。");
        System.out.println("不是：开启后普通 set/get 自动变 Redis 事务。这是新手最大的误解。");
        System.out.println("本章默认不开启，避免误导。");
    }

    /**
     * 小实验：单独构造一个 enableTransactionSupport=true 的 StringRedisTemplate，
     * 但没有外层 Spring 事务时，它的行为与默认情况几乎一致——这是要让新手亲眼看到。
     */
    public void transactionSupportSmallExperiment() {
        StringRedisTemplate flagged = new StringRedisTemplate(connectionFactory);
        flagged.setEnableTransactionSupport(true);
        flagged.afterPropertiesSet();

        String k = L408Keys.txLab(99);
        flagged.delete(k);
        flagged.opsForValue().set(k, "no-outer-tx");
        // 没有外层 Spring 事务，这里 set 不会进入任何 Redis 事务，立即落库。
        String v = flagged.opsForValue().get(k);
        System.out.println("[txSupport-small] value=" + v
                + " (说明：开关没生效，因为没有外层 Spring 事务)");
        flagged.delete(k);
    }

    /**
     * 解释为什么 lab 默认 NOT 启用。
     */
    public void whyNotDefaultEnableInThisLab() {
        System.out.println("=== 为什么本章 RedisTemplate 默认不开 transactionSupport ===");
        System.out.println("1. 实验项目没有外层 Spring 事务，开关大部分时间是空操作；");
        System.out.println("2. 开启后新手容易误以为'普通命令也自动 Redis 事务'，反而误导；");
        System.out.println("3. 本章主线是 SessionCallback 显式事务，更适合学习 multi/exec/watch 的真实路径；");
        System.out.println("4. 真实业务接 @Transactional 时再单独开启即可。");
    }
}
