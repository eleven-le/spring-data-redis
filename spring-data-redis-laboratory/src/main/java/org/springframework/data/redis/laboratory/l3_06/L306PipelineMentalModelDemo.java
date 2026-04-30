package org.springframework.data.redis.laboratory.l3_06;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_06.support.L306RedisConfig;

/**
 * <h3>L3-06 实验 6：pipeline 心智模型 + LettuceConnection 在 pipeline 中的特殊处理</h3>
 *
 * <p>很多人对 pipeline 有两个常见误解：</p>
 * <ul>
 * <li>误解 1：「pipeline 让 Redis 并行执行命令」<br>
 *     真相：Redis 单线程串行执行；pipeline 只是<b>客户端不等回包就连发</b>，节省 RTT。</li>
 * <li>误解 2：「我可以在 pipeline 中间穿插业务读 Redis 的其他操作」<br>
 *     真相：pipeline 期间连接被绑定为 dedicated connection，所有命令都进队列；
 *     pipeline 关闭时才一次性 await + 收集结果。</li>
 * </ul>
 *
 * <h4>LettuceConnection 在 pipeline 场景的关键处理</h4>
 * <ul>
 * <li>{@code openPipeline()} 不只是设置 {@code isPipelined=true}，它还会
 *     {@code getOrCreateDedicatedConnection()} 把 pipeline 固定在专用连接上；</li>
 * <li>每条命令通过 {@code doInvoke} 的 pipeline 分支放进 {@code ppline} 列表，
 *     不立即 await；</li>
 * <li>{@code closePipeline()} 用 {@code LettuceFutures.awaitAll(timeout, ...)} 收割结果，
 *     再把 Lettuce 的 RedisFuture 列表转成 SDR 标准的 List。</li>
 * </ul>
 *
 * <h4>断点建议</h4>
 * <ol>
 * <li>{@code RedisTemplate.executePipelined(RedisCallback, RedisSerializer)}</li>
 * <li>{@code LettuceConnection.openPipeline()} —— 看 asyncDedicatedConn 从 null 变非 null；</li>
 * <li>{@code LettuceConnection.doInvoke(...)} 的 pipeline 分支；</li>
 * <li>{@code LettuceConnection.closePipeline()} —— awaitAll + 转换；</li>
 * <li>{@code RedisPipelineException}（如果命令失败）。</li>
 * </ol>
 *
 * <h4>高并发场景</h4>
 * <ul>
 * <li>合适：批量预热商品详情缓存、批量下发优惠券、批量记录埋点；</li>
 * <li>不合适：链路上每条命令的结果都决定下一条命令（强依赖串行）；</li>
 * <li>不合适：单批次过大（数十万命令），会撑爆 Lettuce 内部 future list 与 Netty Channel buffer。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L306PipelineMentalModelDemo {

	private static final String KEY_PREFIX = "lab:l3_06:pipeline:";

	public static void main(String[] args) {

		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(L306RedisConfig.class)) {

			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			RedisCallback<Object> callback = (connection) -> {
				for (int i = 0; i < 10; i++) {
					byte[] key = (KEY_PREFIX + i).getBytes(StandardCharsets.UTF_8);
					byte[] value = ("v" + i).getBytes(StandardCharsets.UTF_8);
					connection.set(key, value);
				}
				for (int i = 0; i < 10; i++) {
					byte[] key = (KEY_PREFIX + i).getBytes(StandardCharsets.UTF_8);
					connection.get(key);
				}
				// pipeline 中 callback 必须返回 null，否则 SDR 会抛 InvalidDataAccessApiUsageException
				return null;
			};
			List<Object> results = template.executePipelined(callback);

			System.out.println("pipeline 总结果数 = " + results.size());
			System.out.println("前 10 条是 SET 的 OK，后 10 条是 GET 返回的 byte[] 经过反序列化。");
			System.out.println("\n关键观察：整个 pipeline 期间 LettuceConnection.asyncDedicatedConn 不为 null，");
			System.out.println("即使 shareNativeConnection=true，pipeline 也独占一条连接。");
		}
	}
}
