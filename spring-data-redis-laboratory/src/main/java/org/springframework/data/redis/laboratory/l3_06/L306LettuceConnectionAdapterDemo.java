package org.springframework.data.redis.laboratory.l3_06;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_06.support.L306RedisConfig;

/**
 * <h3>L3-06 实验 1：LettuceConnection 适配链路最小实验</h3>
 *
 * <p>这是 L3-06 的「主菜」demo。它不做什么花哨业务，就跑 {@code SET / GET / INCR / EXPIRE}。
 * 但它存在的意义不是验证 Redis 能不能通，而是<b>让你在 IDEA 里亲眼看到「一条命令穿过几层适配」</b>。</p>
 *
 * <h4>断点建议（按顺序往下打）</h4>
 * <ol>
 * <li>{@link org.springframework.data.redis.core.RedisTemplate#execute(org.springframework.data.redis.core.RedisCallback, boolean, boolean)}
 *     —— 模板入口，{@code action.doInRedis(connToExpose)} 是 Callback 回调点；</li>
 * <li>{@link org.springframework.data.redis.core.RedisConnectionUtils#getConnection(org.springframework.data.redis.connection.RedisConnectionFactory, boolean)}
 *     —— 资源获取入口；</li>
 * <li>{@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory#getConnection()}
 *     —— 每次 new 一个 LettuceConnection wrapper（注意是「wrapper」，不是物理连接！）；</li>
 * <li>{@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory}.{@code doCreateLettuceConnection}
 *     —— 真正 new 出 LettuceConnection 的钩子方法；</li>
 * <li>{@code LettuceStringCommands.set(...)} —— 字符串命令的子门面；</li>
 * <li>{@code LettuceConnection.invoke()} 与 {@code doInvoke(...)}
 *     —— 命令分发的核心；它根据 isPipelined / isQueueing 决定怎么处理 Future；</li>
 * <li>{@code LettuceConnection.getAsyncConnection()} —— 同 / 专用连接分流点；</li>
 * <li>{@code LettuceInvoker.just(RedisStringAsyncCommands::set, key, value)}
 *     —— 方法引用驱动 Lettuce 原生 API；</li>
 * <li>Lettuce 原生：{@code RedisAsyncCommandsImpl.set(...)} —— 真正的网络 IO 入口。</li>
 * </ol>
 *
 * <h4>体现的 Spring Framework 思想</h4>
 * <ul>
 * <li><b>IoC 容器</b>：{@code LettuceConnectionFactory} / {@code StringRedisTemplate} 都是普通 Bean；</li>
 * <li><b>InitializingBean</b>：{@code afterPropertiesSet} 完成 RedisClient 创建与 shared connection 注入；</li>
 * <li><b>DisposableBean</b>：context.close() 时回收 Lettuce 客户端、Netty EventLoop；</li>
 * <li><b>Template / Callback</b>：{@code RedisTemplate.execute} 把「拿连接 / 调命令 / 释放 / 异常翻译」抽成模板；</li>
 * <li><b>Adapter</b>：LettuceConnection 是 RedisConnection ↔ Lettuce API 的适配器；</li>
 * <li><b>Factory</b>：RedisConnectionFactory 屏蔽底层客户端选择（Lettuce / Jedis）。</li>
 * </ul>
 *
 * <p>运行前请确保本地 Redis 在 127.0.0.1:6379（或修改 {@code redis.properties}）。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L306LettuceConnectionAdapterDemo {

	private static final String KEY_PREFIX = "lab:l3_06:adapter:";

	public static void main(String[] args) {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(L306RedisConfig.class)) {

			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			/*
			 * 第 1 个断点：opsForValue().set —— 业务层视角。
			 */
			template.opsForValue().set(KEY_PREFIX + "user:1", "Tom");

			// 第 2 个断点：opsForValue().get
			String value = template.opsForValue().get(KEY_PREFIX + "user:1");
			System.out.println("GET " + KEY_PREFIX + "user:1 = " + value);

			// 第 3 个断点：incr —— 不同命令族走不同 LettuceXxxCommands 子类
			template.opsForValue().increment(KEY_PREFIX + "counter");

			// 第 4 个断点：expire —— RedisKeyCommands 命令族
			template.expire(KEY_PREFIX + "user:1", java.time.Duration.ofSeconds(60));

			System.out.println("\n结论：每条命令都经过");
			System.out.println("  StringRedisTemplate -> RedisTemplate.execute -> RedisConnectionUtils");
			System.out.println("  -> LettuceConnectionFactory.getConnection -> LettuceConnection wrapper");
			System.out.println("  -> LettuceXxxCommands -> LettuceConnection.invoke -> LettuceInvoker");
			System.out.println("  -> Lettuce RedisAsyncCommands -> Netty Channel -> Redis Server");
			System.out.println("这就是 L3-06 要让你看清的「适配层穿越」。");
		}
	}
}
