package org.springframework.data.redis.laboratory.l3_06;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_06.support.L306RedisConfig;
import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

/**
 * <h3>L3-06 实验 2：原生 Lettuce API vs Spring Data Redis 抽象</h3>
 *
 * <p>很多新人会问：<b>「我都已经会用 Lettuce 了，为什么还要套一层 Spring Data Redis？」</b>
 * 这个 demo 把两种写法摆在一起，让你亲眼对比。</p>
 *
 * <h4>对比维度</h4>
 * <table border="1">
 * <tr><th>维度</th><th>原生 Lettuce</th><th>Spring Data Redis</th></tr>
 * <tr><td>API 依赖</td><td>{@code io.lettuce.core.*}</td><td>{@code o.s.d.redis.*}</td></tr>
 * <tr><td>连接管理</td><td>业务自己 connect / close</td><td>RedisTemplate.execute 自动 acquire/release</td></tr>
 * <tr><td>异常体系</td><td>RedisException 系列（unchecked）</td><td>统一 DataAccessException（unchecked + DAO 语义）</td></tr>
 * <tr><td>序列化</td><td>需要自己选 RedisCodec</td><td>RedisSerializer 策略 + 默认实现</td></tr>
 * <tr><td>事务集成</td><td>手动 MULTI/EXEC，无 Spring 事务感知</td><td>enableTransactionSupport + Spring 事务同步</td></tr>
 * <tr><td>切换客户端</td><td>业务代码大面积改动</td><td>只改 RedisConnectionFactory Bean</td></tr>
 * <tr><td>Bean 生命周期</td><td>自己管理 RedisClient.shutdown</td><td>容器统一 destroy</td></tr>
 * </table>
 *
 * <h4>什么时候才会直接依赖 Lettuce 原生 API？</h4>
 * <ul>
 * <li>需要使用 SDR 还没适配的命令（例如 6.x 新模块、Stream 高级语法的某些选项）；</li>
 * <li>极致性能要求下手写 pipeline batch flush，需要直接控制 Netty 写入；</li>
 * <li>实现 SDR 自定义扩展（例如自己写 LettuceXxxCommands 子类）。</li>
 * </ul>
 *
 * <p>但即便上面这些场景，建议<b>把原生 Lettuce 用法封装在 Repository / Adapter 内部，
 * 不要让业务代码到处 {@code import io.lettuce.core.*}</b>。否则后期换客户端、加监控、统一异常都极难做。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L306NativeLettuceVsSpringDataDemo {

	private static final String KEY = "lab:l3_06:vs:greeting";

	public static void main(String[] args) {

		System.out.println("=== A. 原生 Lettuce 写法（业务侵入式）===");
		nativeLettuceUsage();

		System.out.println("\n=== B. Spring Data Redis 写法（业务无感知）===");
		springDataUsage();

		System.out.println("\n=== 结论 ===");
		System.out.println("两种写法都能 set/get，但工程化代价完全不同：");
		System.out.println(" - A 把 Lettuce 类型泄漏到业务代码，连接生命周期、异常、序列化都要业务关心；");
		System.out.println(" - B 业务只看见 StringRedisTemplate，所有底层差异都被 LettuceConnection 适配掉。");
		System.out.println("这正是 L3-06 想让你深刻体会的「适配层」价值。");
	}

	/** 原生 Lettuce：所有职责都得自己管。 */
	private static void nativeLettuceUsage() {

		RedisURI redisUri = RedisURI.Builder
				.redis(RedisConfigUtils.getHost(), RedisConfigUtils.getPort())
				.withDatabase(RedisConfigUtils.getDatabase())
				.build();

		// 1. 自己建客户端
		RedisClient client = RedisClient.create(redisUri);

		// 2. 自己 connect、自己 close —— 注意 try-with-resources 包两层
		try (StatefulRedisConnection<String, String> connection = client.connect()) {

			// 3. 同步命令视图：要自己挑 sync / async / reactive
			RedisCommands<String, String> sync = connection.sync();
			sync.set(KEY, "hello-from-lettuce");
			System.out.println("[native] GET = " + sync.get(KEY));

		} catch (Exception ex) {
			// 4. 业务层会被迫感知 io.lettuce.core.RedisException 等异常类型
			System.err.println("[native] error = " + ex);
		} finally {
			// 5. 客户端自己 shutdown，否则 Netty EventLoop 不退出
			client.shutdown();
		}
	}

	/** Spring Data Redis：业务代码只看见 StringRedisTemplate。 */
	private static void springDataUsage() {

		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(L306RedisConfig.class)) {

			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			template.opsForValue().set(KEY, "hello-from-sdr");
			System.out.println("[sdr] GET = " + template.opsForValue().get(KEY));
		}
		// 上面 try-with-resources 走 context.close()，会触发 LettuceConnectionFactory.destroy()
	}
}
