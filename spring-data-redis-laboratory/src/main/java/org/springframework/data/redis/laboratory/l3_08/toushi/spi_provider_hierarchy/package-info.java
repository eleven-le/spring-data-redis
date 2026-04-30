/**
 * <h2>🥷 偷师 #3:SPI Provider 体系(LettuceConnectionProvider 家族)</h2>
 *
 * <p><b>对标 SDR 源码</b>:</p>
 * <ul>
 *   <li>{@code LettuceConnectionProvider}             — 顶层接口</li>
 *   <li>{@code TargetAware}                            — 标记接口,告诉调用方"我支持指定连接类型"</li>
 *   <li>{@code StandaloneConnectionProvider}           — 单机版</li>
 *   <li>{@code ClusterConnectionProvider}              — 集群版</li>
 *   <li>{@code SentinelConnectionProvider}             — 哨兵版</li>
 *   <li>{@code LettucePoolingConnectionProvider}       — 池化装饰</li>
 * </ul>
 *
 * <h3>设计精髓</h3>
 * <ol>
 *   <li><b>策略模式</b>:同一接口,多个实现,容器根据配置自动选</li>
 *   <li><b>装饰器嵌套</b>:Pooling Provider 实际是包了一层 Standalone/Cluster Provider</li>
 *   <li><b>能力暴露用接口而非字段</b>:用 {@code instanceof TargetAware} 判定能力,
 *       而不是用 {@code if (provider.isXxx())} 这种"魔法字段"</li>
 * </ol>
 *
 * <h3>古茗可迁移场景</h3>
 * <ul>
 *   <li><b>多渠道发送器(短信/邮件/Push)</b>:NotifySender 顶层接口 +
 *       SmsSender/EmailSender/PushSender 子类 + RetrySender / RateLimitSender 装饰器</li>
 *   <li><b>多缓存层(L1 本地 + L2 Redis + L3 数据库)</b>:CacheLevelProvider 顶层 +
 *       LocalCacheProvider / RedisCacheProvider / FallbackProvider 装饰</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
package org.springframework.data.redis.laboratory.l3_08.toushi.spi_provider_hierarchy;
