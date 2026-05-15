package org.springframework.data.redis.laboratory.l4.l4_12.demo02;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_12.common.JsonCodec;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;
import org.springframework.data.redis.laboratory.l4.l4_12.common.Topics;
import org.springframework.data.redis.laboratory.l4.l4_12.common.TraceIds;

/**
 * 后台运营服务发布商品变更广播。
 *
 * <p>生产建议：发布动作应放在 DB 事务提交后（事务监听器 / 本地消息表 + 异步任务）。
 * 这里为了简单直接同步 publish——但你在真实系统里要警惕：
 * "DB 还没提交、广播已经发出 -> 消费端回查到旧数据"是经典坑。
 */
public class ProductCacheRefreshPublisher {

    private final StringRedisTemplate template;

    public ProductCacheRefreshPublisher(StringRedisTemplate template) {
        this.template = template;
    }

    public void publish(long productId, long shopId,
                        ProductChangedEvent.ChangeType type, long version) {
        ProductChangedEvent event = new ProductChangedEvent(
                productId, shopId, type, version, System.currentTimeMillis(), TraceIds.next());
        String body = JsonCodec.toJson(event);
        LogPrinter.print("Publisher", "publish -> " + event);
        // 底层走 RedisTemplate.convertAndSend → RedisConnection.publish
        template.convertAndSend(Topics.PRODUCT_CHANGED, body);
    }
}
