package org.springframework.data.redis.laboratory.l4.l4_04.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.config.L404RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_04.zset.L404DelayQueueZSetScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.zset.L404HotContentZSetScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.zset.L404PriorityQueueZSetScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.zset.L404SalesRankingZSetScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.zset.L404ZSetBasicOperationsLab;

import java.time.Duration;
import java.util.Set;

/**
 * ZSet 系列 Debug 入口。
 * <p>
 * 推荐断点：
 * <ul>
 *   <li>{@code RedisTemplate#opsForZSet}</li>
 *   <li>{@code DefaultZSetOperations#incrementScore}</li>
 *   <li>{@code DefaultZSetOperations#reverseRangeWithScores}（看 Tuple → TypedTuple 反序列化）</li>
 *   <li>{@code RedisTemplate#execute}</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection}</li>
 *   <li>{@code LettuceConnection#zSetCommands}</li>
 * </ul>
 */
public class L404ZSetDebugMain {

    public static void main(String[] args) throws Exception {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L404RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            System.out.println("════════ ① 命令全家桶 ════════");
            new L404ZSetBasicOperationsLab(template).runAll();

            System.out.println("\n════════ ② 销量榜 ════════");
            L404SalesRankingZSetScenario rank = new L404SalesRankingZSetScenario(template);
            rank.clearTodayRank();
            rank.increaseSales("珍珠奶茶", 35);
            rank.increaseSales("椰果奶茶", 22);
            rank.increaseSales("杨枝甘露", 41);
            rank.increaseSales("珍珠奶茶", 5); // 累加
            System.out.println("Top3 = " + rank.getTopN(3));
            System.out.println("珍珠奶茶 排名 = " + rank.getItemRank("珍珠奶茶"));
            System.out.println("珍珠奶茶 销量 = " + rank.getItemScore("珍珠奶茶"));
            rank.clearTodayRank();

            System.out.println("\n════════ ③ 内容热榜 ════════");
            L404HotContentZSetScenario hot = new L404HotContentZSetScenario(template);
            hot.increaseHotScore("c-1", 10);
            hot.increaseHotScore("c-2", 25);
            hot.increaseHotScore("c-3", 18);
            hot.increaseHotScore("c-2", 5);
            System.out.println("热榜 Top3 = " + hot.getHotTopN(3));

            System.out.println("\n════════ ④ 延时队列 ════════");
            L404DelayQueueZSetScenario dq = new L404DelayQueueZSetScenario(template);
            dq.clear();
            dq.addTask("close-order-9001", Duration.ofMillis(200));
            dq.addTask("close-order-9002", Duration.ofMillis(400));
            dq.addTask("close-order-9003", Duration.ofSeconds(60)); // 不会到期
            Thread.sleep(600);
            Set<String> got = dq.pollDueTasksWithWarning(10);
            System.out.println("到期已抢=" + got);
            System.out.println("剩余 due=" + dq.getDueTasks(10));
            dq.clear();

            System.out.println("\n════════ ⑤ 优先级队列 ════════");
            L404PriorityQueueZSetScenario pq = new L404PriorityQueueZSetScenario(template);
            pq.addTask("normal-001", 10);
            pq.addTask("vip-002", 100);
            pq.addTask("urgent-003", 999);
            System.out.println("Top3 = " + pq.getHighestPriorityTasks(3));
            System.out.println("抢到 = " + pq.pollHighestPriorityTaskNaive());
            System.out.println("再抢 = " + pq.pollHighestPriorityTaskNaive());
            pq.removeTask("normal-001");

            System.out.println("\n✅ ZSet 链路全跑通。Step Into incrementScore/reverseRangeWithScores 看源码。");
        }
    }
}
