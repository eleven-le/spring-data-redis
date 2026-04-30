package org.springframework.data.redis.laboratory.l3_06;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_06.support.L306RedisConfig;

/**
 * <h3>L3-06 实验 7：Spring Framework 扩展点观察实验</h3>
 *
 * <p>这个 demo 的主角不是 Redis，而是 <b>Spring Framework 自己的扩展点</b>。
 * 它要让你看清：「Spring Data Redis 不是孤立的 Redis 工具包，它深度依赖 Spring Framework 的能力」。</p>
 *
 * <h4>本 demo 集中观察的扩展点</h4>
 * <ol>
 * <li><b>InitializingBean.afterPropertiesSet</b>：Spring 完成依赖注入后回调，
 *     {@link LettuceConnectionFactory#afterPropertiesSet()} 在这里建 RedisClient、
 *     建 ConnectionProvider、（可选）预热 shared connection。</li>
 * <li><b>DisposableBean.destroy</b>：context.close() 时回调，
 *     {@link LettuceConnectionFactory#destroy()} 在这里关闭 client、释放 Netty EventLoop。</li>
 * <li><b>RedisTemplate.afterPropertiesSet</b>：完成 serializer 默认装配 + 标记 initialized，
 *     之后才能 {@code execute(...)}。</li>
 * <li><b>Template / Callback</b>：{@code RedisTemplate.execute(RedisCallback)} 是最经典的范式。</li>
 * <li><b>DataAccessException 体系</b>：Lettuce 异常被 {@code LettuceExceptionConverter}
 *     翻译成 Spring 的 {@code DataAccessException}，业务 catch 同一棵异常树。</li>
 * <li><b>RedisSerializer 策略</b>：StringRedisTemplate 默认用 StringRedisSerializer，
 *     它是 Strategy 模式的典型应用，可以替换成 Jackson / Jdk / GenericJackson 等。</li>
 * <li><b>RedisConnectionFactory 工厂抽象</b>：和 DataSource 在思想上完全一致。</li>
 * </ol>
 *
 * <h4>断点建议</h4>
 * <ol>
 * <li>{@link LettuceConnectionFactory#afterPropertiesSet()}</li>
 * <li>{@link org.springframework.data.redis.core.RedisTemplate#afterPropertiesSet()}</li>
 * <li>{@link org.springframework.data.redis.core.RedisTemplate#execute(org.springframework.data.redis.core.RedisCallback)}</li>
 * <li>{@code LettuceExceptionConverter.convert(Exception)}（故意构造异常时观察）</li>
 * <li>{@link LettuceConnectionFactory#destroy()}（context.close() 时进入）</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L306SpringExtensionPointsDemo {

	public static void main(String[] args) {

		System.out.println("=== 1. context 启动：观察 afterPropertiesSet ===");
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(L306RedisConfig.class);

		// 取 Bean 时，afterPropertiesSet 早已执行完毕
		LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
		StringRedisTemplate template = context.getBean(StringRedisTemplate.class);
		System.out.println("factory ready? underlying client = " + factory.getNativeClient());

		System.out.println("\n=== 2. 正常命令：体会 Template / Callback ===");
		template.opsForValue().set("lab:l3_06:ext:greeting", "hi");
		System.out.println("GET = " + template.opsForValue().get("lab:l3_06:ext:greeting"));

		System.out.println("\n=== 3. 故意触发命令异常：观察异常翻译 ===");
		try {
			// 用错类型的命令：先 SET 一个 String，再用 LPUSH 强行追加，会抛 WRONGTYPE
			template.opsForValue().set("lab:l3_06:ext:wrongtype", "stringValue");
			template.opsForList().leftPush("lab:l3_06:ext:wrongtype", "x");
		} catch (DataAccessException ex) {
			System.out.println("捕获到 DataAccessException = " + ex.getClass().getSimpleName());
			System.out.println("根因 = " + (ex.getCause() == null ? "(none)" : ex.getCause().getClass().getName()));
			System.out.println("说明 LettuceExceptionConverter 已经把 Lettuce 异常翻译成 Spring DAO 异常了。");
		}

		System.out.println("\n=== 4. 显式 close context：观察 destroy ===");
		// 这里显式调用，方便你在 LettuceConnectionFactory.destroy 上断点
		context.close();
		System.out.println("context closed. factory destroyed.");
	}
}
