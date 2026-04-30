package org.springframework.data.redis.laboratory.l3_05;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_05.support.L305RedisConfig;

/**
 * L3-05 实验 2：{@code executePipelined(...)} 触发 dedicated connection。
 *
 * <p>Spring Data Redis 2.7.18 的关键源码事实：
 * <pre>{@code
 * RedisTemplate.executePipelined(...)
 *   -> RedisTemplate.execute(...)
 *   -> connection.openPipeline()
 *   -> LettuceConnection.openPipeline()
 *   -> flushState.onOpen(this.getOrCreateDedicatedConnection())
 *   -> asyncDedicatedConn = doGetAsyncDedicatedConnection()
 * }</pre>
 *
 * <p>为什么 pipeline 不能简单走共享连接？pipeline 的本质是在同一连接上连续排队多个命令，
 * 最后统一收响应。它需要一个清晰的命令批次边界，不能让其他业务线程的普通 GET/SET 混入这个批次。</p>
 *
 * <p>推荐断点：
 * <ol>
 * <li>{@code RedisTemplate.executePipelined(RedisCallback, RedisSerializer)}</li>
 * <li>{@code RedisTemplate.execute(RedisCallback, boolean, boolean)}</li>
 * <li>{@code LettuceConnection.openPipeline()}</li>
 * <li>{@code LettuceConnection.getOrCreateDedicatedConnection()}</li>
 * <li>{@code LettuceConnection.doGetAsyncDedicatedConnection()}</li>
 * <li>{@code LettucePoolingConnectionProvider.getConnection(Class)}：如果你把配置切成池化版，会在这里 borrow。</li>
 * <li>{@code LettuceConnection.close()} 与 {@code RedisConnectionUtils.releaseConnection(...)}：观察释放。</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L305PipelineDedicatedConnectionDemo {

	private static final String KEY_PREFIX = "lab:l3_05:pipeline:";

	public static void main(String[] args) {

		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(L305RedisConfig.class)) {

			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			List<Object> results = template.executePipelined((RedisCallback<Object>) connection -> {
				connection.set(raw(KEY_PREFIX + "1"), raw("A"));
				connection.set(raw(KEY_PREFIX + "2"), raw("B"));
				connection.get(raw(KEY_PREFIX + "1"));
				connection.get(raw(KEY_PREFIX + "2"));
				return null;
			});

			System.out.println("pipeline results = " + results);
			System.out.println("结论：executePipelined 打开 pipeline 时，当前版本会立即懒加载 asyncDedicatedConn。");
		}
	}

	private static byte[] raw(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
