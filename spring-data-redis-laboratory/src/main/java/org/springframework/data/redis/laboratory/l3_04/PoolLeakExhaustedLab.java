package org.springframework.data.redis.laboratory.l3_04;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;

/**
 * 实验③：连接泄漏 = 忘记 returnObject。
 *
 * <p>很多新人在 try / catch 写错了 finally，借出来的连接没归还。
 * 表现：池容量没改、并发没涨，但接口越来越慢，最后所有请求超时。
 *
 * <p>本实验对照演示：
 * 1) 错误写法：借了不还 → 第 3 次 borrow 直接 timeout
 * 2) 正确写法：try / finally return → 一切正常
 */
public class PoolLeakExhaustedLab {

	public static void main(String[] args) throws Exception {
		System.out.println("============ 1. 错误写法：忘记 returnObject ============");
		runLeakyClient();

		System.out.println();
		System.out.println("============ 2. 正确写法：try / finally returnObject ============");
		runCorrectClient();
	}

	private static void runLeakyClient() {
		GenericObjectPoolConfig<MockRedisConnection> cfg = new GenericObjectPoolConfig<>();
		cfg.setMaxTotal(2);
		cfg.setMaxWait(Duration.ofMillis(800));
		cfg.setJmxEnabled(false);

		try (GenericObjectPool<MockRedisConnection> pool =
				new GenericObjectPool<>(new MockRedisConnectionFactory(), cfg)) {

			MockRedisConnection a = borrow(pool, "biz-1");
			MockRedisConnection b = borrow(pool, "biz-2");
			a.exec("INCR sku:1");
			b.exec("INCR sku:2");
			// ❌ 故意不归还，模拟泄漏（return 写在了 try 块内部，遇异常就跳过）

			try {
				borrow(pool, "biz-3");
			} catch (Exception e) {
				System.out.println("[biz-3] 借不到连接：" + e);
			}

			System.out.println(">> 池状态：active=" + pool.getNumActive() + ", idle=" + pool.getNumIdle()
					+ "   ← active 没归零，就是泄漏的指纹");
		}
	}

	private static void runCorrectClient() throws Exception {
		GenericObjectPoolConfig<MockRedisConnection> cfg = new GenericObjectPoolConfig<>();
		cfg.setMaxTotal(2);
		cfg.setMaxWait(Duration.ofMillis(800));
		cfg.setJmxEnabled(false);

		try (GenericObjectPool<MockRedisConnection> pool =
				new GenericObjectPool<>(new MockRedisConnectionFactory(), cfg)) {

			for (int i = 1; i <= 5; i++) {
				MockRedisConnection conn = pool.borrowObject();
				try {
					conn.exec("INCR k" + i);
				} finally {
					pool.returnObject(conn);          // ✅ 正确姿势
				}
			}
			System.out.println(">> 池状态：active=" + pool.getNumActive() + ", idle=" + pool.getNumIdle()
					+ "   ← active=0 就是健康的池");
		}
	}

	private static MockRedisConnection borrow(GenericObjectPool<MockRedisConnection> pool, String tag) {
		try {
			MockRedisConnection conn = pool.borrowObject();
			System.out.println("[" + tag + "] borrowed " + conn);
			return conn;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
