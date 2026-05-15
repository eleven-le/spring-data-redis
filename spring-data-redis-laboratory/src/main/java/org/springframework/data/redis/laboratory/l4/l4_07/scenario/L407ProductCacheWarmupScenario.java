package org.springframework.data.redis.laboratory.l4.l4_07.scenario;

import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407Pipelines;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_07.L407Keys;
import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.BatchMetrics;
import org.springframework.data.redis.laboratory.l4.l4_07.pipeline.L407PipelineBatchExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 场景 6.2：商品详情批量补缓存 / 大促前预热。
 * <p>
 * <b>业务背景</b>：大促前 30 分钟，需要把 5 万个 SKU 的商品详情批量加载到 Redis。
 * 早期版本一条 SET + 一条 EXPIRE 写两次，5 万 SKU = 10 万命令；
 * 切到"set with TTL"后是 5 万命令；
 * 再切到"Pipeline 分批 set with TTL"后是 ~50 个 RTT，整体耗时从 50s 压到 1s。
 * <p>
 * 关键决策：
 * 1) 用"set with TTL"取代"set + expire"，避免 set 成功 expire 失败留下永久 key；
 * 2) TTL 加随机值，避免大批量同时过期触发雪崩；
 * 3) 分批写，单批 500-1000，避免客户端内存与 Redis 输出缓冲尖峰。
 */
public class L407ProductCacheWarmupScenario {

    private final StringRedisTemplate template;

    public L407ProductCacheWarmupScenario(StringRedisTemplate template) {
        this.template = template;
    }

    public record Product(long productId, String detailJson) {
    }

    /**
     * 一次性预热（不分批，仅作对照）。
     */
    public List<Object> warmupProducts(List<Product> products) {
        return L407Pipelines.run(template, ops -> {
            for (Product p : products) {
                ops.opsForValue().set(buildProductCacheKey(p.productId()),
                        p.detailJson(),
                        randomTtl(Duration.ofMinutes(30)));
            }
            return null;
        });
    }

    /**
     * 推荐用法：分批预热 + 指标统计。
     * <p>
     * BatchExecutor 把"分批 + 计时 + 异常吞吐"抽走了，业务只关心 lambda 内部"这一批怎么写"。
     */
    public BatchMetrics warmupProductsInBatches(List<Product> products, int batchSize) {
        L407PipelineBatchExecutor<Product, List<Object>> executor = new L407PipelineBatchExecutor<>(batchSize);

        return executor.executeInBatchesWithMetrics(products, batch ->
                L407Pipelines.run(template, ops -> {
                    for (Product p : batch) {
                        ops.opsForValue().set(
                                this.buildProductCacheKey(p.productId()),  //key
                                p.detailJson(),   //value
                                randomTtl(Duration.ofMinutes(30))); //ttl
                    }
                    return null;
                })
        );
    }

    public String buildProductCacheKey(long productId) {
        return L407Keys.productDetail(productId);
    }

    /**
     * 在 baseTtl 上加 +/- 10% 随机抖动，缓解雪崩。
     * 真实业务可以按业务等级分多档（高频商品短 TTL，长尾商品长 TTL）。
     */
    public Duration randomTtl(Duration baseTtl) {
        long base = baseTtl.toMillis(); //将传入的基础过期时间（Duration 类型）转换为毫秒（long）。转换为毫秒是为了后续能够进行更细粒度的数值计算。
        //计算出抖动范围。这里硬编码了 0.1d，表示抖动范围是基础 TTL 的 10%。例如，如果基础 TTL 是 10000 毫秒（10秒），那么 jitter 就是 1000 毫秒。
        long jitter = (long) (base * 0.1d);
        //这是最关键的一行。取值范围：生成一个在 [-10%, +10%] 范围内的随机偏移量（+1 是因为 nextLong 是左闭右开区间）。
        //并发性能：使用了 ThreadLocalRandom 而不是 Math.random() 或共享的 new Random()。在多线程高并发场景下，ThreadLocalRandom 为每个线程维护一个独立的种子，避免了多个线程竞争同一个锁，极大提升了并发性能。
        long delta = ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        //将计算出的 基础时间 + 随机偏移量 重新封装成 Duration 对象返回。最终得到的 TTL 会在 [baseTtl * 0.9, baseTtl * 1.1] 之间浮动。
        return Duration.ofMillis(base + delta);
/*
加上 10% 的随机抖动后，原本集中在同一秒失效的 key，被均匀地打散到了一个时间段（比如 54 分钟到 66 分钟之间）内失效。这样就把数据库的瞬间 QPS 洪峰削平了，变成了平缓的请求曲线。

这段代码目前是一个通用方法，但在复杂的电商或交易系统中，仅仅加随机抖动是不够的，TTL 的设计需要结合业务特性：
    1. 高频热点数据：TTL 可以设置得相对较短，保证数据一致性，但必须配合随机抖动，甚至配合逻辑过期（缓存永不失效，后台异步线程更新）来彻底解决击穿和雪崩问题。
    2. 长尾冷门数据：TTL 可以设置得长很多（比如几天），避免频繁地从 DB 加载无人问津的数据，浪费资源。
    优化建议：目前 10% (0.1d) 的抖动比例是写死的，在实际工程中，建议将这个比例提取为配置项（如通过 Nacos/Apollo 下发），或者作为方法的参数传入，以便针对不同业务场景动态调整。*/
    }

    /**
     * 工具：生成 demo 商品列表。
     */
    public static List<Product> buildDemoProducts(int count) {
        List<Product> list = new ArrayList<>(count);
        for (long i = 1; i <= count; i++) {
            list.add(new Product(i, "{\"id\":" + i + ",\"name\":\"sku-" + i + "\"}"));
        }
        return list;
    }
}
