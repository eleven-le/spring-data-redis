package org.springframework.data.redis.laboratory.l3_04;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;

/**
 * Apache Commons Pool 2 的 PooledObjectFactory：
 * 告诉 GenericObjectPool 怎么创建/校验/销毁 {@link MockRedisConnection}。
 *
 * <p>对应 Lettuce 里的 {@code () -> connectionProvider.getConnection(StatefulConnection.class)}：
 * 池只关心"对象生产线"，至于具体是 Redis 还是 HTTP 客户端，它根本不在乎。
 */
public class MockRedisConnectionFactory extends BasePooledObjectFactory<MockRedisConnection> {

	@Override
	public MockRedisConnection create() {
		MockRedisConnection conn = new MockRedisConnection();
		System.out.println("    [factory] CREATE  -> " + conn + "  thread=" + Thread.currentThread().getName());
		return conn;
	}

	@Override
	public PooledObject<MockRedisConnection> wrap(MockRedisConnection obj) {
		return new DefaultPooledObject<>(obj);
	}

	/** 对应连接的 borrow 前校验（test-on-borrow / test-while-idle 都会用到）。 */
	@Override
	public boolean validateObject(PooledObject<MockRedisConnection> p) {
		boolean ok = p.getObject().isAlive();
		System.out.println("    [factory] VALIDATE -> " + p.getObject() + "  result=" + ok);
		return ok;
	}

	@Override
	public void destroyObject(PooledObject<MockRedisConnection> p) {
		System.out.println("    [factory] DESTROY -> " + p.getObject() + "  thread=" + Thread.currentThread().getName());
		p.getObject().close();
	}
}
