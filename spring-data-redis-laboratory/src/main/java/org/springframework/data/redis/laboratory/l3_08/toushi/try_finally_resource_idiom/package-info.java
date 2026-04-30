/**
 * <h2>🥷 偷师 #5:try-finally 资源释放范式 + execute(callback) API 设计</h2>
 *
 * <p><b>对标 SDR 源码</b>:{@code RedisTemplate#execute(RedisCallback)} 内部 finally 块。</p>
 *
 * <h3>核心范式(Spring 全家桶通用)</h3>
 *
 * <pre><code>
 * public &lt;T&gt; T execute(Callback&lt;T&gt; callback) {
 *     Resource res = acquire();
 *     try {
 *         return callback.doIn(res);    // 业务在这里
 *     } finally {
 *         release(res);                  // 框架兜底,业务不用管
 *     }
 * }
 * </code></pre>
 *
 * <h3>价值</h3>
 * <ul>
 *   <li><b>API 极简</b>:业务只写 callback,不写资源管理代码</li>
 *   <li><b>异常安全</b>:无论 callback 抛什么,finally 必走</li>
 *   <li><b>生命周期统一</b>:框架可在 acquire/release 周围加埋点、链路追踪、限流、降级</li>
 *   <li><b>变种丰富</b>:JdbcTemplate / TransactionTemplate / RestTemplate / KafkaTemplate
 *       全部用同款,把它当作"框架的 GoF 模式"</li>
 * </ul>
 *
 * <h3>古茗可迁移场景</h3>
 * <ul>
 *   <li><b>分布式锁模板</b>:{@code distributedLockTemplate.execute(key, () -> 业务)}</li>
 *   <li><b>幂等模板</b>:{@code idempotencyTemplate.execute(token, () -> 业务)}</li>
 *   <li><b>租户切换模板</b>:{@code tenantTemplate.executeAs(tenantId, () -> 业务)}</li>
 *   <li><b>降级模板</b>:{@code fallbackTemplate.execute(() -> primary, () -> fallback)}</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
package org.springframework.data.redis.laboratory.l3_08.toushi.try_finally_resource_idiom;
