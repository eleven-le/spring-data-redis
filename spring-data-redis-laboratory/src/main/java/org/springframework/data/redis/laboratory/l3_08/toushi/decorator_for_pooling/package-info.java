/**
 * <h2>🥷 偷师 #4:Decorator 装饰器叠加池化能力</h2>
 *
 * <p><b>对标 SDR 源码</b>:{@code LettucePoolingConnectionProvider} 在内部持有一个
 * {@code connectionProvider}(可能是 Standalone/Cluster/Sentinel),
 * 借连接时<b>不直接 connect</b>,而是通过池子调用底层 provider 的 connect。</p>
 *
 * <pre><code>
 * GenericObjectPool&lt;StatefulConnection&lt;?, ?&gt;&gt; pool = ConnectionPoolSupport.createGenericObjectPool(
 *     () -&gt; connectionProvider.getConnection(connectionType),    // 委托
 *     poolConfig,
 *     false);
 * </code></pre>
 *
 * <h3>设计精髓</h3>
 * <ul>
 *   <li>"池化"本身是一种<b>横切能力</b>(orthogonal),不应硬编码到 Standalone/Cluster Provider 里</li>
 *   <li>装饰器把"池化"剥成独立类,可任意叠加到任何底层 Provider 之上</li>
 *   <li><b>裸用 Standalone</b> = 每次新建连接;<b>用 Pooling 包一层</b> = 池化复用</li>
 * </ul>
 *
 * <h3>古茗可迁移场景</h3>
 * <ul>
 *   <li><b>缓存装饰器</b>:CachingHttpClient 包裹普通 HttpClient,
 *       原始 client 不感知缓存逻辑</li>
 *   <li><b>限流装饰器</b>:RateLimitedSender 包裹底层 Sender,SPI 切入限流</li>
 *   <li><b>追踪装饰器</b>:TracingDecorator 包裹任何 RPC 客户端,自动注入 traceId</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
package org.springframework.data.redis.laboratory.l3_08.toushi.decorator_for_pooling;
