package org.springframework.data.redis.laboratory.l3_05;

import java.util.List;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_05.support.L305RedisConfig;

/**
 * L3-05 实验 3：{@link SessionCallback} + {@code MULTI / EXEC} 触发 dedicated connection。
 *
 * <p>事务是连接级状态：{@code WATCH}、{@code MULTI}、后续命令队列、{@code EXEC} 必须在同一条
 * Redis 连接上完成。Spring Data Redis 因此会用 {@code RedisConnectionHolder} 把同一个
 * {@code LettuceConnection} wrapper 绑定到当前线程，避免 SessionCallback 中每次 template 调用都换 wrapper。</p>
 *
 * <p>推荐断点：
 * <ol>
 * <li>{@code RedisTemplate.execute(SessionCallback)}</li>
 * <li>{@code RedisConnectionUtils.bindConnection(...)}</li>
 * <li>{@code RedisConnectionUtils.doGetConnection(...)}：观察 {@code TransactionSynchronizationManager.getResource(factory)}</li>
 * <li>{@code LettuceConnection.multi()}</li>
 * <li>{@code LettuceConnection.getDedicatedConnection()}</li>
 * <li>{@code LettuceConnection.getOrCreateDedicatedConnection()}</li>
 * <li>{@code LettuceConnection.exec()}</li>
 * <li>{@code RedisConnectionUtils.unbindConnection(...)}</li>
 * </ol>
 *
 * <p>源码关系：
 * <pre>{@code
 * SessionCallback
 *   -> bindConnection(factory)
 *   -> TransactionSynchronizationManager.bindResource(factory, RedisConnectionHolder)
 *   -> multi() sets isMulti=true
 *   -> transaction commands use dedicated connection
 * }</pre>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L305TransactionDedicatedConnectionDemo {

	private static final String KEY = "lab:l3_05:tx";

	public static void main(String[] args) {

		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(L305RedisConfig.class)) {

			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			List<Object> txResults = template.execute(new SessionCallback<List<Object>>() {
				@Override
				@SuppressWarnings({ "unchecked", "rawtypes" })
				public List<Object> execute(RedisOperations operations) {
					operations.multi();
					operations.opsForValue().set(KEY, "tx-value");
					operations.opsForValue().get(KEY);
					return operations.exec();
				}
			});

			System.out.println("transaction results = " + txResults);
			System.out.println("结论：事务上下文是连接级状态，MULTI/EXEC 路径必须固定到 dedicated connection。");
		}
	}
}
