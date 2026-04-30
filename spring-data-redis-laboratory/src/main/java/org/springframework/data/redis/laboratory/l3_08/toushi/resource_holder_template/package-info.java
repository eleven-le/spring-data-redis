/**
 * <h2>🥷 偷师 #1:Resource Holder Template(资源持有者模板)</h2>
 *
 * <p><b>对标 SDR 源码</b>:{@code RedisConnectionUtils} + {@code RedisConnectionHolder}<br>
 * <b>对标 Spring 通用</b>:{@code DataSourceUtils} + {@code ConnectionHolder} +
 *                          {@code ResourceHolderSupport} + {@code TransactionSynchronizationManager}</p>
 *
 * <h3>模式骨架</h3>
 * <ol>
 *   <li>{@code XxxUtils#getResource}    — 从 ThreadLocal 找,没有就新建并 bind</li>
 *   <li>{@code XxxUtils#releaseResource} — ref-- 减到 0 才真关</li>
 *   <li>{@code XxxHolder}                — 内部维护 referenceCount(借给嵌套调用复用)</li>
 *   <li>事务钩子                          — {@code afterCompletion} 解绑</li>
 * </ol>
 *
 * <h3>古茗可迁移的同构场景</h3>
 * <ul>
 *   <li><b>支付会话 Token</b>:同一线程发起多个支付校验时,Token 只生成一次,
 *       嵌套调用复用同一个;事务结束统一作废。</li>
 *   <li><b>租户上下文(Multi-Tenant)</b>:跨多个 Service 调用同一租户的资源,
 *       ThreadLocal 持有 + ref count,避免每层方法重建上下文。</li>
 *   <li><b>分布式锁的本地 Owner 上下文</b>:嵌套加锁(可重入)就是引用计数的另一面。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
package org.springframework.data.redis.laboratory.l3_08.toushi.resource_holder_template;
