/**
 * L4-07 偷师设计思想 demo 包。
 * <p>
 * 4 个 demo 分别从 Pipeline 源码偷师 4 种结构：
 * <ul>
 *   <li>{@link org.springframework.data.redis.laboratory.l4.l4_07.toushi.BatchTemplateCallbackDemo}
 *       —— Template + Callback：把"资源生命周期"封死在模板，业务只写 callback；</li>
 *   <li>{@link org.springframework.data.redis.laboratory.l4.l4_07.toushi.BatchExecutorDesignDemo}
 *       —— 通用分批执行器：分批 + 指标 + 异常吞吐解耦；</li>
 *   <li>{@link org.springframework.data.redis.laboratory.l4.l4_07.toushi.ResultMapperDesignDemo}
 *       —— 命令计划 + 结果映射：业务签名永远不暴露 List&lt;Object&gt;；</li>
 *   <li>{@link org.springframework.data.redis.laboratory.l4.l4_07.toushi.BatchStrategyDemo}
 *       —— Strategy：MGET / Pipeline / Loop 切换不影响业务代码。</li>
 * </ul>
 */
package org.springframework.data.redis.laboratory.l4.l4_07.toushi;
