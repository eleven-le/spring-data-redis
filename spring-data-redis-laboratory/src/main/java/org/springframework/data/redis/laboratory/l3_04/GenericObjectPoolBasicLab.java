package org.springframework.data.redis.laboratory.l3_04;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/**
 * 实验①：把 GenericObjectPool 完整跑一遍，看清 borrow / return / 复用 三件事。
 *
 * <p>预期现象：
 * <pre>
 *   [factory] CREATE -> MockRedisConnection#1     ← 第 1 次 borrow，触发新建
 *   borrow #1: MockRedisConnection#1
 *   return #1
 *   borrow #2: MockRedisConnection#1              ← 直接复用，没再 CREATE
 *   borrow #3: MockRedisConnection#2              ← 第 1 个还没归还，新建第 2 个
 *   ...
 * </pre>
 */
public class GenericObjectPoolBasicLab {

	public static void main(String[] args) throws Exception {
		GenericObjectPoolConfig<MockRedisConnection> cfg = new GenericObjectPoolConfig<>();
		cfg.setMaxTotal(3);   // ≈ Lettuce spring.redis.lettuce.pool.max-active
		cfg.setMaxIdle(3);    // 最多保留多少 idle
		cfg.setMinIdle(0);    // 不预热
		cfg.setJmxEnabled(false);

		try (GenericObjectPool<MockRedisConnection> pool = new GenericObjectPool<>(new MockRedisConnectionFactory(), cfg)) {

			System.out.println("=== 1. 单借单还：复用同一个连接 ===");
			for (int i = 1; i <= 3; i++) {
				MockRedisConnection c = pool.borrowObject();
				System.out.println("borrow #" + i + ": " + c + "  (active=" + pool.getNumActive() + ", idle=" + pool.getNumIdle() + ")");
				c.exec("PING");
				pool.returnObject(c);
				System.out.println("return #" + i + ":         (active=" + pool.getNumActive() + ", idle=" + pool.getNumIdle() + ")");
			}

			System.out.println();
			System.out.println("=== 2. 同时持有多条：触发新建 ===");
			MockRedisConnection a = pool.borrowObject();
			MockRedisConnection b = pool.borrowObject();
			MockRedisConnection c = pool.borrowObject();
			System.out.println("now:  active=" + pool.getNumActive() + ", idle=" + pool.getNumIdle());

			pool.returnObject(a);
			pool.returnObject(b);
			pool.returnObject(c);
			System.out.println("after return all: active=" + pool.getNumActive() + ", idle=" + pool.getNumIdle());
		}
	}
}
