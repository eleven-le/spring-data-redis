package org.springframework.data.redis.laboratory.l3_05;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l3_05.support.L305RedisConfig;

/**
 * L3-05 实验 4：SCAN Cursor 与 sticky connection 生命周期。
 *
 * <p>SCAN 不是一次性把所有 key 全部返回，而是返回一个 cursor，后续 {@code cursor.next()} 可能继续向
 * Redis 发送 SCAN 请求。因此 Spring Data Redis 不能在 {@code RedisTemplate.scan(...)} 返回时马上释放
 * connection，而是使用 {@code executeWithStickyConnection(...)} 把连接生命周期交给 Cursor。</p>
 *
 * <p>推荐断点：
 * <ol>
 * <li>{@code RedisTemplate.scan(ScanOptions)}</li>
 * <li>{@code RedisTemplate.executeWithStickyConnection(RedisCallback)}</li>
 * <li>{@code RedisConnectionUtils.doGetConnection(factory, true, false, false)}：注意 bind=false</li>
 * <li>{@code LettuceKeyCommands.scan(ScanOptions)}</li>
 * <li>{@code LettuceKeyCommands.doScan(ScanOptions)}</li>
 * <li>{@code LettuceKeyCommands匿名LettuceScanCursor.doClose()}</li>
 * <li>{@code LettuceConnection.close()}</li>
 * </ol>
 *
 * <p>重要区别：SCAN 本身在当前版本通常走 {@code connection.invoke()}，也就是普通命令选择逻辑；
 * 它不等于必然创建 {@code asyncDedicatedConn}。它真正危险的是 sticky connection 被 Cursor 持有，
 * 如果 Cursor 不关闭，连接 wrapper 无法释放；当 {@code shareNativeConnection=false} 或 scan 内部已经使用
 * dedicated 路径时，就可能表现为池资源泄漏。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class L305ScanCursorStickyConnectionDemo {

	public static void main(String[] args) {

		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(L305RedisConfig.class)) {

			StringRedisTemplate template = context.getBean(StringRedisTemplate.class);

			template.opsForValue().set("lab:l3_05:scan:1", "A");
			template.opsForValue().set("lab:l3_05:scan:2", "B");

			ScanOptions options = ScanOptions.scanOptions()
					.match("lab:l3_05:scan:*")
					.count(10)
					.build();

			try (Cursor<String> cursor = template.scan(options)) {
				while (cursor.hasNext()) {
					System.out.println("scan key = " + cursor.next());
				}
			}

			System.out.println("结论：scan 返回 Cursor 后必须 try-with-resources 关闭，释放 sticky connection。");
		}
	}
}
