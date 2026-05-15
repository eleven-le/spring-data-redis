package org.springframework.data.redis.laboratory.l4.l4_08.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.config.L408RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408OptimisticRetryTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408OptimisticRetryTemplate.OptimisticResult;
import org.springframework.data.redis.laboratory.l4.l4_08.watch.L408WatchBasicLab;

import java.time.Duration;

/**
 * WATCH 调试主入口。
 * <p>
 * <b>建议断点</b>：
 * <ul>
 *   <li>{@code RedisOperations#watch / unwatch}</li>
 *   <li>{@code LettuceConnection#watch / unwatch}</li>
 *   <li>{@code RedisOperations#exec} → {@code LettuceConnection#exec}</li>
 *   <li>{@code DefaultValueOperations#get}（在 MULTI 前 vs 后行为不同）</li>
 * </ul>
 */
public class L408WatchDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L408RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);
            L408WatchBasicLab lab = new L408WatchBasicLab(template);

            System.out.println("===== L4-08 Watch Debug =====");

            System.out.println("[1] watchSingleKeyExample = " + lab.watchSingleKeyExample());
            System.out.println("[2] watchMultiKeysExample = " + lab.watchMultiKeysExample());
            lab.unwatchExample();
            System.out.println("[3] unwatchExample done");
            System.out.println("[4] execSuccessWhenNoConflict = " + lab.execSuccessWhenNoConflict());
            System.out.println("[5] execFailWhenWatchedKeyChanged = " + lab.execFailWhenWatchedKeyChanged());
            System.out.println("[6] getBeforeMultiCorrect value = " + lab.getBeforeMultiCorrectExample());
            System.out.println("[7] getAfterMultiWrong value (expect null) = " + lab.getAfterMultiWrongExample());

            // 演示乐观重试模板
            L408OptimisticRetryTemplate retry = new L408OptimisticRetryTemplate();
            OptimisticResult<String> r = retry.executeWithBackoff(3, Duration.ofMillis(5),
                    () -> {
                        // 这里业务侧可以放任意"可能因为 WATCH 冲突返回 null"的事务逻辑
                        return "ok";
                    });
            System.out.println("[8] OptimisticRetryTemplate result = " + r);
            System.out.println("    metrics = " + retry.getMetrics());

            lab.cleanupAll();
            System.out.println("===== Watch Debug Done =====");
        }
    }
}
