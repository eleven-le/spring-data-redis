package org.springframework.data.redis.laboratory.l3_05;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_05.support.L305RedisConfig;

/**
 * L3-05 实验 1：普通 GET / SET 默认走 shared native connection。
 *
 * <p>本实验的目标不是证明 Redis 能读写，而是让你在 IDEA 里断点跟进去，看清普通命令不会
 * 主动创建 {@code asyncDedicatedConn}。</p>
 *
 * <p>推荐断点：
 * <ol>
 * <li>{@code RedisTemplate.execute(RedisCallback, boolean, boolean)}</li>
 * <li>{@code RedisConnectionUtils.getConnection(RedisConnectionFactory, boolean)}</li>
 * <li>{@code RedisConnectionUtils.doGetConnection(...)}</li>
 * <li>{@code LettuceConnectionFactory.getConnection()}</li>
 * <li>{@code LettuceConnection.getAsyncConnection()}</li>
 * <li>{@code LettuceConnection.getAsyncDedicatedConnection()}：普通命令不应该进入这里，除非
 * {@code shareNativeConnection=false} 或 shared connection 不可用。</li>
 * </ol>
 *
 * <p>观察点：
 * <pre>{@code
 * asyncSharedConn != null
 * isQueueing() == false
 * isPipelined() == false
 * getAsyncConnection() 返回 asyncSharedConn.async()
 * }</pre>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L305NormalSharedConnectionDemo {

	private static final String KEY = "lab:l3_05:normal";

	public static void main(String[] args) {

		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(L305RedisConfig.class)) {

			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			template.opsForValue().set(KEY, "shared-connection");
			String value = template.opsForValue().get(KEY);

			System.out.println("GET result = " + value);
			System.out.println("结论：普通 GET / SET 通过 RedisTemplate.execute 进入 LettuceConnection，");
			System.out.println("在 shareNativeConnection=true 时优先使用 asyncSharedConn，不创建 asyncDedicatedConn。");
		}
	}
}
