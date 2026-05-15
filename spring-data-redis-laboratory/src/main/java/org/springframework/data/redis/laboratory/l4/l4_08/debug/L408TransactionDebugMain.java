package org.springframework.data.redis.laboratory.l4.l4_08.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.config.L408RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408TransactionBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408TransactionResultMappingLab;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408TransactionSupportLab;

import java.util.List;

/**
 * 事务源码调试主入口。
 * <p>
 * <b>建议断点</b>（按调用顺序由外到内）：
 * <ol>
 *   <li>{@code RedisTemplate#execute(SessionCallback)}</li>
 *   <li>{@code RedisTemplate#execute(RedisCallback, boolean, boolean)}</li>
 *   <li>{@code RedisConnectionUtils#bindConnection / unbindConnection / doGetConnection}</li>
 *   <li>{@code RedisOperations#multi / exec / discard / watch / unwatch}</li>
 *   <li>{@code LettuceConnection#multi / exec / discard / watch / unwatch}</li>
 *   <li>{@code DefaultValueOperations#set / increment}（看入队）</li>
 *   <li>{@code RedisTemplate#execRaw}</li>
 *   <li>{@code RedisTemplate#deserializeMixedResults}</li>
 * </ol>
 */
public class L408TransactionDebugMain {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L408RedisConfig.class)) {

            StringRedisTemplate stringTemplate = ctx.getBean(StringRedisTemplate.class);
            RedisTemplate<String, Object> objectTemplate =
                    (RedisTemplate<String, Object>) ctx.getBean("redisTemplate");
            LettuceConnectionFactory factory = ctx.getBean(LettuceConnectionFactory.class);

            L408TransactionBasicOperationsLab basic = new L408TransactionBasicOperationsLab(stringTemplate);
            L408TransactionResultMappingLab mapping =
                    new L408TransactionResultMappingLab(stringTemplate, objectTemplate);
            L408TransactionSupportLab support = new L408TransactionSupportLab(factory);

            System.out.println("===== L4-08 Transaction Debug =====");

            System.out.println("[1] multiExecBasicExample = " +
                    L408TransactionBasicOperationsLab.previewResults(basic.multiExecBasicExample()));

            System.out.println("[2] multiDiscardExample (no exec)");
            basic.multiDiscardExample();

            System.out.println("[3] transactionMixedResultsExample = " +
                    L408TransactionBasicOperationsLab.previewResults(basic.transactionMixedResultsExample()));

            System.out.println("[4] transactionRuntimeErrorNoRollbackExample (注意：第 2 条会报 WRONGTYPE)");
            try {
                List<Object> r = basic.transactionRuntimeErrorNoRollbackExample();
                System.out.println("    EXEC raw = " + L408TransactionBasicOperationsLab.previewResults(r));
            } catch (Exception e) {
                System.out.println("    EXEC throw = " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            System.out.println("[5] transactionQueueingBehaviorExample = " +
                    L408TransactionBasicOperationsLab.previewResults(basic.transactionQueueingBehaviorExample()));

            System.out.println("[6] transactionWithStringOperations = " +
                    L408TransactionBasicOperationsLab.previewResults(basic.transactionWithStringOperations()));

            System.out.println("[7] transactionWithHashOperations = " +
                    L408TransactionBasicOperationsLab.previewResults(basic.transactionWithHashOperations()));

            System.out.println("[8] transactionWithZSetOperations = " +
                    L408TransactionBasicOperationsLab.previewResults(basic.transactionWithZSetOperations()));

            System.out.println("[9] redisCallbackTransactionExample = " +
                    basic.redisCallbackTransactionExample());

            System.out.println("[10] sessionCallbackTransactionExample = " +
                    L408TransactionBasicOperationsLab.previewResults(basic.sessionCallbackTransactionExample()));

            System.out.println("--- result mapping ---");
            List<Object> mapped = mapping.mapExecResultsByCommandOrder();
            System.out.println("[11] mapExecResultsByCommandOrder = " + mapped);
            System.out.println("[12] demonstrateMixedResultTypes = " + mapping.demonstrateMixedResultTypes());
            mapping.demonstrateWrongCastPitfall(mapped);
            System.out.println("[13] demonstrateExecNullConflictResult = " + mapping.demonstrateExecNullConflictResult());
            mapping.explainSerializerEffect();

            System.out.println("--- transactionSupport ---");
            support.explainTransactionSupportConcept();
            support.transactionSupportSmallExperiment();
            support.whyNotDefaultEnableInThisLab();

            // 清理
            basic.cleanupAll();
            mapping.cleanupAll();

            System.out.println("===== Transaction Debug Done =====");
        }
    }
}
