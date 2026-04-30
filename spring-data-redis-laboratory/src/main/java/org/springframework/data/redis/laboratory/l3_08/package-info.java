/**
 * <h2>L3-08 / SDR-17 连接生命周期源码精读</h2>
 *
 * <p>主题:把一条 Redis 连接从「出生 → 体检 → 服役 → 退伍 → 火化」的全路径,
 * 用源码 + Demo + 偷师三联打通。这是 RedisTemplate 热路径背后最容易出血的环节,
 * 古茗 C 端的所有缓存/库存/活动都跑在这条管子上。</p>
 *
 * <h3>章节包结构</h3>
 * <ul>
 *   <li><b>{@link org.springframework.data.redis.laboratory.l3_08.support}</b>
 *       — Spring 配置(LettuceConnectionFactory、RedisTemplate),所有 Demo 共用容器入口。</li>
 *   <li><b>{@link org.springframework.data.redis.laboratory.l3_08.creation}</b>
 *       🐣 创建阶段:谁在什么时机造连接?afterPropertiesSet / lazy 共享连接 / 池化 borrow。</li>
 *   <li><b>{@link org.springframework.data.redis.laboratory.l3_08.validation}</b>
 *       ✅ 验证阶段:testOnBorrow / testWhileIdle 四开关 + Lettuce PING + Evictor 后台线程。</li>
 *   <li><b>{@link org.springframework.data.redis.laboratory.l3_08.usage}</b>
 *       🛠️ 使用阶段:RedisConnectionUtils / ConnectionHolder 引用计数 / 共享多路复用判定 /
 *       MULTI、BLPOP、SUBSCRIBE 必须独占连接的边界。</li>
 *   <li><b>{@link org.springframework.data.redis.laboratory.l3_08.release}</b>
 *       🔚 释放阶段:finally 释放是 SDR 团队最重要的一行代码;泄漏复现 + 引用计数归零判定。</li>
 *   <li><b>{@link org.springframework.data.redis.laboratory.l3_08.eviction}</b>
 *       💀 驱逐阶段:Evictor 后台线程节奏 + minIdle/maxIdle 博弈 + Netty EventLoop 优雅关闭。</li>
 *   <li><b>{@link org.springframework.data.redis.laboratory.l3_08.toushi}</b>
 *       🥷 偷师专区:5 个可迁移到自家项目的设计范式
 *       (ResourceHolder Template、ObjectPool、SPI Provider、Decorator、try-finally + execute(callback))。</li>
 * </ul>
 *
 * <h3>断点四件套(本章节最值得标的源码点)</h3>
 * <ol>
 *   <li>{@code LettuceConnectionFactory#afterPropertiesSet}     — Bean 生命周期触发的连接预热</li>
 *   <li>{@code LettuceConnectionFactory#getConnection}          — 共享 vs 池化的分流入口</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection}            — ThreadLocal Holder + 事务绑定</li>
 *   <li>{@code RedisConnectionUtils#releaseConnection}          — 引用计数归零才真正归还</li>
 *   <li>{@code GenericObjectPool#borrowObject / evict}          — 池化的两段心跳</li>
 *   <li>{@code LettuceConnectionFactory#destroy}                — 关闭客户端 + EventLoopGroup</li>
 * </ol>
 *
 * <h3>运行前置</h3>
 * <ul>
 *   <li>本地 Redis: 127.0.0.1:6379(由 {@code redis.properties} 覆盖)</li>
 *   <li>JDK 17 + Maven,直接在 IDEA 里 Run main 方法即可</li>
 *   <li>所有 Demo 都用 {@link org.springframework.context.annotation.AnnotationConfigApplicationContext},
 *       走完整 Spring Bean 生命周期(才能命中 afterPropertiesSet/destroy 断点)</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
package org.springframework.data.redis.laboratory.l3_08;
