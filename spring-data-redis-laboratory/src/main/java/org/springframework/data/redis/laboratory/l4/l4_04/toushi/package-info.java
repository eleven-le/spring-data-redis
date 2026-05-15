/**
 * L4-04 偷师包 —— 把 Spring Data Redis 在 List/Set/ZSet 上展现的设计思想，
 * 抽离出 Redis 业务细节，沉淀成可复用到 C 端业务代码的"通用结构"：
 * <ul>
 *   <li>{@link org.springframework.data.redis.laboratory.l4.l4_04.toushi.OperationsSplitDesignDemo}
 *       —— 仿 opsForList / opsForSet / opsForZSet 的"按能力拆门面" 思路；</li>
 *   <li>{@link org.springframework.data.redis.laboratory.l4.l4_04.toushi.TemplateCallbackDemo}
 *       —— 仿 RedisTemplate.execute(callback) 的"模板管资源 + 回调写业务" 思路；</li>
 *   <li>{@link org.springframework.data.redis.laboratory.l4.l4_04.toushi.RankingStrategyDemo}
 *       —— 仿 ZSet 排行榜里 score 计算被多种策略复用的"策略模式" 思路；</li>
 *   <li>{@link org.springframework.data.redis.laboratory.l4.l4_04.toushi.BoundResourceOperationsDemo}
 *       —— 仿 BoundListOperations / BoundZSetOperations 的"按资源绑定 key 形成领域对象" 思路。</li>
 * </ul>
 * 每个 Demo 都有 main 可独立运行；不依赖 Redis，专注表达"结构本身"。
 */
package org.springframework.data.redis.laboratory.l4.l4_04.toushi;
