/**
 * <h2>🥷 偷师 #2:Object Pool 通用对象池抽象</h2>
 *
 * <p><b>对标 SDR / Lettuce / Apache Commons</b>:
 * {@code GenericObjectPool} + {@code PooledObjectFactory} + {@code EvictionPolicy}</p>
 *
 * <h3>核心抽象(三大角色)</h3>
 * <ol>
 *   <li><b>Pool</b>:{@code borrow / return / evict} 三方法</li>
 *   <li><b>Factory</b>:{@code create / validate / destroy} — 把对象生命周期交给池</li>
 *   <li><b>EvictionPolicy</b>:SPI,决定"什么样的对象该被驱逐"</li>
 * </ol>
 *
 * <h3>古茗可迁移场景</h3>
 * <ul>
 *   <li><b>HTTP 客户端连接池</b>:OkHttp 的 ConnectionPool 是同款做法,理解了它能自定义校验策略
 *       (比如把"上次请求 RT > 200ms 的连接"提前驱逐)</li>
 *   <li><b>RPC 长连接池</b>:Dubbo/gRPC channel 池本质相同</li>
 *   <li><b>BigDecimal/StringBuilder 等重对象池</b>:内部计算密集场景常见</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
package org.springframework.data.redis.laboratory.l3_08.toushi.object_pool_generic;
