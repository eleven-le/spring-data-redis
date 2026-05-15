package org.springframework.data.redis.laboratory.l4.l4_04.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.config.L404RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_04.set.L404DailyCheckinSetScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.set.L404LikeSetScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.set.L404LotterySetScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.set.L404SetBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_04.set.L404TagIntersectionScenario;

/**
 * Set 系列 Debug 入口。
 * <p>
 * 推荐断点：
 * <ul>
 *   <li>{@code RedisTemplate#opsForSet}</li>
 *   <li>{@code DefaultSetOperations#add}</li>
 *   <li>{@code DefaultSetOperations#isMember}</li>
 *   <li>{@code RedisTemplate#execute}（finally 内 RedisConnectionUtils.releaseConnection）</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection}</li>
 *   <li>{@code LettuceConnection#setCommands}</li>
 * </ul>
 */
public class L404SetDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L404RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            System.out.println("════════ ① 命令全家桶 ════════");
            new L404SetBasicOperationsLab(template).runAll();

            System.out.println("\n════════ ② 每日签到 ════════");
            L404DailyCheckinSetScenario ck = new L404DailyCheckinSetScenario(template);
            ck.clearToday();
            System.out.println("user1 首次签到=" + ck.checkin("u-1"));
            System.out.println("user1 重复签到=" + ck.checkin("u-1"));
            ck.checkin("u-2");
            ck.checkin("u-3");
            System.out.println("当日 UV=" + ck.countToday());
            System.out.println("user1 是否签到=" + ck.hasCheckedIn("u-1"));
            ck.clearToday();

            System.out.println("\n════════ ③ 抽奖池 ════════");
            L404LotterySetScenario lot = new L404LotterySetScenario(template);
            String act = "act-2026-05-01";
            lot.clear(act);
            for (int i = 1; i <= 10; i++) lot.join(act, "u-" + i);
            System.out.println("候选 3 人 = " + lot.randomCandidates(act, 3));
            System.out.println("中奖一人 = " + lot.drawOne(act));
            System.out.println("再开 3 人 = " + lot.drawMany(act, 3));
            System.out.println("剩余池容 = " + lot.poolSize(act));
            lot.clear(act);

            System.out.println("\n════════ ④ 点赞 ════════");
            L404LikeSetScenario like = new L404LikeSetScenario(template);
            String item = "post-9001";
            like.like(item, "u-1");
            like.like(item, "u-1"); // 幂等
            like.like(item, "u-2");
            System.out.println("点赞数=" + like.countLikes(item));
            System.out.println("u-1 是否点赞=" + like.hasLiked(item, "u-1"));
            like.unlike(item, "u-1");
            System.out.println("取消后点赞数=" + like.countLikes(item));

            System.out.println("\n════════ ⑤ 标签交并差 ════════");
            L404TagIntersectionScenario tag = new L404TagIntersectionScenario(template);
            tag.addUserToTag("milk", "u-1");
            tag.addUserToTag("milk", "u-2");
            tag.addUserToTag("milk", "u-3");
            tag.addUserToTag("fruit", "u-2");
            tag.addUserToTag("fruit", "u-3");
            tag.addUserToTag("fruit", "u-4");
            System.out.println("milk ∩ fruit = " + tag.commonUsers("milk", "fruit"));
            System.out.println("milk ∪ fruit = " + tag.unionUsers("milk", "fruit"));
            System.out.println("milk - fruit = " + tag.diffUsers("milk", "fruit"));

            System.out.println("\n✅ Set 链路全跑通。Step Into ops.add / ops.isMember 看源码。");
        }
    }
}
