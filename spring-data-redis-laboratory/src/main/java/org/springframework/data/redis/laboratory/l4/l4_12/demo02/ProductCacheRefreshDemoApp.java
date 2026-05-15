package org.springframework.data.redis.laboratory.l4.l4_12.demo02;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.util.concurrent.TimeUnit;

/**
 * 入口：商品缓存刷新广播完整链路演示。
 *
 * <p>预期日志序列：
 * 1. 后台修改价格 -> repo.updatePrice 返回 v=2
 * 2. publisher 发出广播
 * 3. Node-A、Node-B 两个 listener 都打印 [apply] ... v=2
 * 4. 重复发布同一个 v=2 -> 两个节点都打印 [skip]（幂等生效）
 * 5. 价格再次更新到 v=3 -> 两个节点 [apply]
 * 6. 缓存快照打印
 *
 * <p>关键观察：listener 收到 v=2 的旧广播时不会回退本地的 v=3 缓存。
 */
public class ProductCacheRefreshDemoApp {

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext ctx =
                new AnnotationConfigApplicationContext(Demo02Config.class);
        ctx.registerShutdownHook();

        ProductRepository repo = ctx.getBean(ProductRepository.class);
        ProductCacheRefreshPublisher publisher = ctx.getBean(ProductCacheRefreshPublisher.class);
        LocalProductCache nodeA = ctx.getBean("cacheNodeA", LocalProductCache.class);
        LocalProductCache nodeB = ctx.getBean("cacheNodeB", LocalProductCache.class);

        TimeUnit.MILLISECONDS.sleep(500); // 等订阅就绪

        // ① 第一次价格变更：2800 -> 3200
        long v2 = repo.updatePrice(1001L, 3200);
        publisher.publish(1001L, 88L, ProductChangedEvent.ChangeType.PRICE, v2);
        TimeUnit.MILLISECONDS.sleep(300);

        // ② 重复发同样的 v2：应该被两个节点 skip
        LogPrinter.print("App", "--- duplicate publish (same version) ---");
        publisher.publish(1001L, 88L, ProductChangedEvent.ChangeType.PRICE, v2);
        TimeUnit.MILLISECONDS.sleep(300);

        // ③ 第二次价格变更：3200 -> 2999
        long v3 = repo.updatePrice(1001L, 2999);
        publisher.publish(1001L, 88L, ProductChangedEvent.ChangeType.PRICE, v3);
        TimeUnit.MILLISECONDS.sleep(300);

        // ④ 模拟乱序：补发一条 v=2 旧广播，应该全部 skip
        LogPrinter.print("App", "--- out-of-order publish (older version) ---");
        publisher.publish(1001L, 88L, ProductChangedEvent.ChangeType.PRICE, v2);
        TimeUnit.MILLISECONDS.sleep(500);

        LogPrinter.print("App", "--- final cache snapshots ---");
        nodeA.printSnapshot();
        nodeB.printSnapshot();

        ctx.close();
    }
}
