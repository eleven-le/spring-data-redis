package org.springframework.data.redis.laboratory.l4.l4_04.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.config.L404RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_04.list.L404FeedTimelineListScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.list.L404ListBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_04.list.L404OrderFulfillmentQueueScenario;
import org.springframework.data.redis.laboratory.l4.l4_04.list.L404RecentViewListScenario;

import java.time.Duration;
import java.util.List;

/**
 * List 系列 Debug 入口。
 * <p>
 * 推荐断点：
 * <ul>
 *   <li>{@code RedisTemplate#opsForList}（看 ListOperations 子门面如何懒生成）</li>
 *   <li>{@code DefaultListOperations#rightPush}</li>
 *   <li>{@code DefaultListOperations#leftPop}</li>
 *   <li>{@code RedisTemplate#execute(RedisCallback,boolean,boolean)}（资源管理 finally）</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection}（ConnectionHolder 复用）</li>
 *   <li>{@code LettuceConnection#listCommands}（byte[] 命令分流）</li>
 * </ul>
 */
public class L404ListDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L404RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            System.out.println("════════ ① 命令全家桶 ════════");
            new L404ListBasicOperationsLab(template).runAll();

            System.out.println("\n════════ ② 订单履约队列 ════════");
            L404OrderFulfillmentQueueScenario q = new L404OrderFulfillmentQueueScenario(template);
            q.clearQueue();
            q.enqueue("ORD-1001");
            q.enqueue("ORD-1002");
            q.enqueue("ORD-1003");
            System.out.println("队列长度=" + q.queueSize());
            System.out.println("出队1=" + q.dequeue());
            System.out.println("阻塞出队2=" + q.blockingDequeue(Duration.ofSeconds(1)));
            q.requeue("ORD-1004"); // 模拟失败重入
            System.out.println("重入后剩余=" + q.queueSize());
            q.clearQueue();

            System.out.println("\n════════ ③ 最近浏览 ════════");
            L404RecentViewListScenario rv = new L404RecentViewListScenario(template);
            String userId = "u-2001";
            rv.clearViews(userId);
            rv.addView(userId, "item-A");
            rv.addView(userId, "item-B");
            rv.addView(userId, "item-A"); // 重复访问 A，应去重并提到最前
            rv.addView(userId, "item-C");
            System.out.println("最近浏览=" + rv.getRecentViews(userId, 5));
            rv.clearViews(userId);

            System.out.println("\n════════ ④ Feed 时间线 ════════");
            L404FeedTimelineListScenario feed = new L404FeedTimelineListScenario(template);
            String fid = "u-3001";
            feed.clear(fid);
            feed.pushFeed(fid, "post-1");
            feed.pushFeed(fid, "post-2");
            feed.pushFeeds(fid, List.of("post-3", "post-4", "post-5"));
            System.out.println("时间线 0..4=" + feed.getTimeline(fid, 0, 4));
            feed.clear(fid);

            System.out.println("\n✅ List 链路全跑通。Step Into ops.rightPush 看源码。");
        }
    }
}
