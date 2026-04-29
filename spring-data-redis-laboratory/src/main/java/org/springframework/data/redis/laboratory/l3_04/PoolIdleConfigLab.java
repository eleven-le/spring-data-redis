package org.springframework.data.redis.laboratory.l3_04;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;

/**
 * 实验④：直观理解 maxIdle / minIdle / 空闲驱逐 三件套。
 *
 * <p>场景翻译到 C 端：活动期间 Tomcat 突然来了 10 个并发，活动结束后回落到 0。
 * - maxIdle 太低：活动结束后池里一个连接都不留，下次峰值要从 0 重新建
 * - minIdle 太高：平时也维持 N 条连接，大促前可以预热但平时浪费
 * - 空闲驱逐：检测到长期没用的 idle 连接，主动 destroy，避免拿到坏连接
 */
public class PoolIdleConfigLab {

	public static void main(String[] args) throws Exception {
		GenericObjectPoolConfig<MockRedisConnection> cfg = new GenericObjectPoolConfig<>();
		cfg.setMaxTotal(10);
		cfg.setMaxIdle(3);                                          // 最多保留 3 条 idle
		cfg.setMinIdle(2);                                          // 至少维持 2 条 idle（活动前预热）
		cfg.setTimeBetweenEvictionRuns(Duration.ofMillis(500));     // 每 500ms 跑一轮驱逐
		cfg.setMinEvictableIdleTime(Duration.ofMillis(1500));       // idle 超过 1.5s 就被 evict
		cfg.setJmxEnabled(false);

		try (GenericObjectPool<MockRedisConnection> pool =
				new GenericObjectPool<>(new MockRedisConnectionFactory(), cfg)) {

			System.out.println("=== 模拟活动峰值：一次性借 6 条 ===");
			MockRedisConnection[] arr = new MockRedisConnection[6];
			for (int i = 0; i < arr.length; i++) {
				arr[i] = pool.borrowObject();
			}
			System.out.println("active=" + pool.getNumActive() + " idle=" + pool.getNumIdle());

			System.out.println("\n=== 活动结束：6 条全归还，但 maxIdle=3 ===");
			for (MockRedisConnection c : arr) {
				pool.returnObject(c);
			}
			System.out.println("立刻看 idle 数量 = " + pool.getNumIdle()
					+ "   ← 多出来的 (6-3) 条会立刻 DESTROY，不进 idle");

			System.out.println("\n=== 等 3 秒，看驱逐线程把 idle 缩到 minIdle=2 ===");
			Thread.sleep(3000);
			System.out.println("3 秒后 idle = " + pool.getNumIdle() + "   ← 长期没用的 idle 会被驱逐到 minIdle 为止");
		}
	}
}
