package org.springframework.data.redis.laboratory.l4.l4_12.demo02;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.laboratory.l4.l4_12.common.JsonCodec;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.nio.charset.StandardCharsets;

/**
 * 节点级 listener：负责把广播转成"刷新本地缓存"动作。
 *
 * <p>遵守的生产纪律：
 * <ul>
 *   <li>不相信消息体的字段，只用消息体里的 productId 去 repo 回查</li>
 *   <li>用 version 做幂等，防重复消费 + 防乱序覆盖</li>
 *   <li>traceId 全程透传，日志能跨节点对账</li>
 *   <li>异常吞掉并打日志，避免一条脏消息把 listener 线程打死（线上更稳的做法是
 *       把异常抛回容器，让全局 ErrorHandler 统一处理——见 demo04）</li>
 * </ul>
 */
public class ProductCacheRefreshListener implements MessageListener {

    private final LocalProductCache cache;
    private final ProductRepository repo;

    public ProductCacheRefreshListener(LocalProductCache cache, ProductRepository repo) {
        this.cache = cache;
        this.repo = repo;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            ProductChangedEvent event = JsonCodec.fromJson(body, ProductChangedEvent.class);
            String tag = "Listener-" + cache.getNodeName();

            // 第一道幂等：消息版本 ≤ 本地版本，直接丢弃，连回查都不做
            LocalProductCache.CachedProduct local = cache.get(event.getProductId());
            if (local != null && event.getVersion() <= local.version) {
                LogPrinter.print(tag, "[skip] traceId=" + event.getTraceId() +
                        " productId=" + event.getProductId() +
                        " msg.v=" + event.getVersion() + " local.v=" + local.version);
                return;
            }

            // 回查主数据。这里也可能拿到比消息更新的版本（说明又有人改了），没关系
            ProductRepository.ProductRow row = repo.findById(event.getProductId());
            if (row == null) {
                cache.evict(event.getProductId());
                LogPrinter.print(tag, "[evict] productId=" + event.getProductId());
                return;
            }

            // 第二道幂等：以 repo 当前 version 为准
            LocalProductCache.CachedProduct toCache = new LocalProductCache.CachedProduct(
                    row.productId, row.name, row.priceCent, row.onShelf, row.version);
            boolean applied = cache.putIfNewer(toCache);
            LogPrinter.print(tag, (applied ? "[apply] " : "[stale] ") +
                    "traceId=" + event.getTraceId() +
                    " productId=" + row.productId +
                    " repo.v=" + row.version +
                    " priceCent=" + row.priceCent);
        } catch (Exception e) {
            LogPrinter.print("Listener-ERR", "node=" + cache.getNodeName() +
                    " body=" + body + " err=" + e.getMessage());
        }
    }
}
