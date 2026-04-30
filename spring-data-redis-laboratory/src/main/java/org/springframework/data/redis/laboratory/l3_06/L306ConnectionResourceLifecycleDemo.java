package org.springframework.data.redis.laboratory.l3_06;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisConnectionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_06.support.L306RedisConfig;

/**
 * <h3>L3-06 实验 5：连接资源生命周期实验</h3>
 *
 * <p>这个 demo 把「手动管理连接」和「Template 管理连接」放在一起对比，让你深刻体会
 * Spring 为什么要发明 Template / Callback 这种范式。</p>
 *
 * <h4>核心比较</h4>
 * <ul>
 * <li>A 段：手动 {@code factory.getConnection()} → try-finally → close。
 *     一旦你忘了 close，shared 连接虽然不会泄漏，但 dedicated（pipeline/事务/blocking）
 *     借出去的连接<b>会真的回不到池子</b>，最终连接池耗尽。</li>
 * <li>B 段：用 RedisTemplate.execute。<b>你完全不写一句 close、一行 finally</b>，
 *     Template 在内部的 finally 里走 RedisConnectionUtils.releaseConnection。</li>
 * </ul>
 *
 * <h4>断点建议</h4>
 * <ol>
 * <li>A 段：{@code LettuceConnectionFactory.getConnection()} → {@code LettuceConnection.close()}
 *     → {@code LettuceConnection.reset()}（注意：shared 不会真关）；</li>
 * <li>B 段：{@code RedisTemplate.execute(RedisCallback, ...)} 进入后看 finally 块；</li>
 * <li>{@code RedisConnectionUtils.releaseConnection} 与 {@code DataSourceUtils.releaseConnection}
 *     的设计如出一辙：先看 ThreadLocal holder 是否绑定过，再决定要不要真的关。</li>
 * </ol>
 *
 * <h4>高并发风险点</h4>
 * <ul>
 * <li>持有 dedicated 连接做长任务：连接池 active 数飙升、borrow 排队，最终雪崩；</li>
 * <li>异常路径漏 close：在 try-finally 之外 throw，连接对象悬挂等 GC，但 Lettuce 内部
 *     的 future 可能仍在等回包，超时之后才释放，业务表现是连接「忽多忽少」。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L306ConnectionResourceLifecycleDemo {

	private static final String KEY = "lab:l3_06:lifecycle";

	public static void main(String[] args) {

		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(L306RedisConfig.class)) {

			RedisConnectionFactory factory = context.getBean(RedisConnectionFactory.class);
			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			System.out.println("=== A. 手动管理 RedisConnection ===");
			manualLifecycle(factory);

			System.out.println("\n=== B. RedisTemplate 接管资源生命周期 ===");
			templateLifecycle(template);

			System.out.println("\n结论：A/B 两段最终调用的底层方法几乎一样，但 B 段的 finally 已经");
			System.out.println("被 RedisTemplate.execute 帮你写好了——这就是 Template / Callback 思想。");
		}
	}

	/** 手动版：必须自己 try-finally。这里如果忘了 release，dedicated 连接会泄漏。 */
	private static void manualLifecycle(RedisConnectionFactory factory) {

		// 第 1 个断点：getConnection
		RedisConnection connection = RedisConnectionUtils.getConnection(factory);
		try {
			connection.set(KEY.getBytes(StandardCharsets.UTF_8), "manual".getBytes(StandardCharsets.UTF_8));
			byte[] raw = connection.get(KEY.getBytes(StandardCharsets.UTF_8));
			System.out.println("[manual] GET = " + (raw == null ? null : new String(raw, StandardCharsets.UTF_8)));
		} finally {
			// 第 2 个断点：releaseConnection —— 类比 DataSourceUtils.releaseConnection
			RedisConnectionUtils.releaseConnection(connection, factory);
		}
	}

	/** Template 版：业务代码完全不出现 close。 */
	private static void templateLifecycle(StringRedisTemplate template) {

		// 第 3 个断点：RedisTemplate.execute(RedisCallback) 内部 finally
		RedisCallback<Void> callback = (connection) -> {
			connection.set(KEY.getBytes(StandardCharsets.UTF_8), "template".getBytes(StandardCharsets.UTF_8));
			byte[] raw = connection.get(KEY.getBytes(StandardCharsets.UTF_8));
			System.out.println("[template] GET = " + (raw == null ? null : new String(raw, StandardCharsets.UTF_8)));
			return null;
		};
		template.execute(callback);
	}
}
