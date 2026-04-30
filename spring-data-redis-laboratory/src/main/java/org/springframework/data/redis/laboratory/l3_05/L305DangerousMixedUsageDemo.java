package org.springframework.data.redis.laboratory.l3_05;

import java.util.List;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_05.support.L305RedisConfig;

/**
 * L3-05 实验 5：生产中不要随便混用的复杂连接语义示例。
 *
 * <p>这个类的目的不是推荐写法，而是把新手最容易犯错的组合摆出来：
 * <ul>
 * <li>{@code SessionCallback} 会通过 {@code RedisConnectionHolder} 把连接 wrapper 绑定到当前线程；</li>
 * <li>{@code MULTI} 会让 {@code LettuceConnection.isQueueing()} 变成 true，并切到 dedicated connection；</li>
 * <li>{@code executePipelined} 会打开 pipeline，也需要 dedicated connection；</li>
 * <li>{@code scan/cursor} 需要 sticky connection，并且当前版本在 pipeline/transaction 中会拒绝 SCAN；</li>
 * <li>这些语义嵌套后，连接生命周期、响应结果、异常释放都变得很难推理。</li>
 * </ul>
 *
 * <p>断点建议：
 * <pre>{@code
 * RedisTemplate.execute(SessionCallback)
 * RedisConnectionUtils.bindConnection
 * LettuceConnection.multi
 * RedisTemplate.executePipelined
 * LettuceConnection.openPipeline
 * LettuceKeyCommands.doScan
 * RedisConnectionUtils.unbindConnection
 * LettuceConnection.close
 * }</pre>
 *
 * <p>生产建议：把普通短命令、pipeline 批处理、事务、游标扫描、阻塞队列消费拆成独立、清晰的业务方法；
 * 每个方法只承担一种连接语义。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L305DangerousMixedUsageDemo {

	public static void main(String[] args) {

		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(L305RedisConfig.class)) {

			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			System.out.println("下面的代码是风险演示，不是生产推荐写法。");
			System.out.println("默认只运行一个可控的 pipeline + transaction 示例；scan 嵌套示例保留为注释。");

			List<Object> results = template.execute(new SessionCallback<List<Object>>() {
				@Override
				@SuppressWarnings({ "rawtypes", "unchecked" })
				public List<Object> execute(RedisOperations operations) {

					operations.multi();
					operations.opsForValue().set("lab:l3_05:mixed:tx", "1");

					/*
					 * 不建议在事务 SessionCallback 中再开启 scan/cursor。
					 *
					 * 当前版本 LettuceKeyCommands.doScan(...) 会判断：
					 * if (connection.isQueueing() || connection.isPipelined()) {
					 *     throw new UnsupportedOperationException("'SCAN' cannot be called in pipeline / transaction mode.");
					 * }
					 *
					 * 即使某些路径没有立即抛错，也会让连接 holder、sticky connection、dedicated connection
					 * 的生命周期相互叠加，排查泄漏时非常痛苦。
					 */
					// operations.scan(ScanOptions.scanOptions().match("lab:l3_05:*").build());

					return operations.exec();
				}
			});

			System.out.println("transaction results = " + results);

			List<Object> pipelineResults = template.executePipelined((RedisCallback<Object>) connection -> {
				connection.set("lab:l3_05:mixed:pipeline".getBytes(), "1".getBytes());
				return null;
			});

			System.out.println("pipeline results = " + pipelineResults);
			System.out.println("结论：复杂连接语义要拆开，尤其不要在高并发主链路把 scan/pipeline/transaction 混成一团。");
		}
	}
}
