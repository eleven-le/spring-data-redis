package org.springframework.data.redis.laboratory.l3_04;

import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

/**
 * 实验⑤：testOnBorrow 是怎么救命的。
 *
 * <p>剧情：
 * 1) 借出一条连接、归还后回到 idle
 * 2) 服务端把这条连接断了（模拟）
 * 3) 下一次 borrow 时——
 *    关掉 testOnBorrow：业务拿到坏连接，第一条 INCR 直接异常
 *    打开 testOnBorrow：池在 borrow 前 validate，发现坏，DESTROY 后再 CREATE 一条新的
 */
public class PoolValidateOnBorrowLab {

	public static void main(String[] args) throws Exception {
		System.out.println("============ A. testOnBorrow = false（默认） ============");
		runWithValidate(false);

		System.out.println();
		System.out.println("============ B. testOnBorrow = true ============");
		runWithValidate(true);
	}

	private static void runWithValidate(boolean validate) throws Exception {
		GenericObjectPoolConfig<MockRedisConnection> cfg = new GenericObjectPoolConfig<>();
		cfg.setMaxTotal(2);
		cfg.setTestOnBorrow(validate);
		cfg.setJmxEnabled(false);

		try (GenericObjectPool<MockRedisConnection> pool =
				new GenericObjectPool<>(new MockRedisConnectionFactory(), cfg)) {

			MockRedisConnection conn = pool.borrowObject();
			pool.returnObject(conn);                    // 回到 idle
			conn.breakIt();                             // 模拟服务端关连接 / 网络抖动 / TTL 到期

			MockRedisConnection next = pool.borrowObject();
			try {
				System.out.println(">> 业务拿到 " + next + "，开始执行");
				System.out.println(">> exec result = " + next.exec("INCR x"));
			} catch (Exception e) {
				System.out.println(">> ❌ 业务踩到坏连接：" + e);
			} finally {
				pool.returnObject(next);
			}
		}
	}
}
