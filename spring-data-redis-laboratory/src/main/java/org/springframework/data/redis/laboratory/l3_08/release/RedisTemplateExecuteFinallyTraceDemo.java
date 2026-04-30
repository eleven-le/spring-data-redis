package org.springframework.data.redis.laboratory.l3_08.release;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_08.support.L308RedisConfig;

/**
 * <h3>L3-08 / 🔚 释放阶段 · 实验 3:RedisTemplate#execute 内部 finally 全景追踪</h3>
 *
 * <p>{@code RedisTemplate#execute(RedisCallback, ...)} 的代码骨架(看 SDR 源码 ~217 行附近):</p>
 *
 * <pre>
 * public &lt;T&gt; T execute(RedisCallback&lt;T&gt; action, boolean exposeConnection, boolean pipeline) {
 *     RedisConnectionFactory factory = getRequiredConnectionFactory();
 *     RedisConnection conn = RedisConnectionUtils.getConnection(factory);   // 借
 *     try {
 *         RedisConnection connToUse = preProcessConnection(conn, false);
 *         boolean pipelineStatus = connToUse.isPipelined();
 *         if (pipeline AND !pipelineStatus) connToUse.openPipeline();
 *
 *         RedisConnection connToExpose = exposeConnection ? connToUse : createRedisConnectionProxy(connToUse);
 *         T result = action.doInRedis(connToExpose);                        // 用
 *
 *         if (pipeline AND !pipelineStatus) connToUse.closePipeline();
 *         return postProcessResult(result, connToUse, false);
 *     } finally {
 *         RedisConnectionUtils.releaseConnection(conn, factory);            // 还
 *     }
 * }
 * </pre>
 *
 * <h4>这条 finally 就是 SDR 的"安全带"</h4>
 * <ul>
 *   <li>callback 内抛任何异常 - finally 仍执行 - 连接归还</li>
 *   <li>callback 启动子线程持有 conn 的引用 - 子线程那边的命令可能 fail-fast(连接已关)</li>
 *   <li>这就是为什么 SDR 反复强调:不要在 callback 里把 connection 引用传出去</li>
 * </ul>
 *
 * <h4>断点指引</h4>
 * <ul>
 *   <li>{@code RedisTemplate.execute(RedisCallback)}                         - 顶层入口</li>
 *   <li>{@code RedisTemplate.execute(RedisCallback, boolean, boolean)}        - 看 finally 块</li>
 *   <li>{@code RedisTemplate.preProcessConnection / postProcessResult}        - Template Method 钩子,
 *       子类可重写做埋点</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class RedisTemplateExecuteFinallyTraceDemo {

    public static void main(String[] args) {

        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(L308RedisConfig.class)) {

            StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

            System.out.println("🚦 STEP-1: callback 正常返回 → finally 释放");
            template.execute((RedisCallback<Void>) c -> { c.ping(); return null; });

            System.out.println("🚦 STEP-2: callback 抛异常 → finally 仍释放");
            try {
                template.execute((RedisCallback<Void>) c -> {
                    c.ping();
                    throw new RuntimeException("业务异常");
                });
            } catch (Exception e) {
                System.out.println("           捕获到异常: " + e.getMessage());
            }

            System.out.println("🚦 STEP-3: 再来一次正常调用 → 连接还能正常借出(证明上一步释放成功)");
            template.execute((RedisCallback<Void>) c -> { c.ping(); return null; });

            System.out.println("\n✅ Template 帮你写好了 finally,这是「执行模板 + 回调」范式的核心价值。");
        }
    }
}
