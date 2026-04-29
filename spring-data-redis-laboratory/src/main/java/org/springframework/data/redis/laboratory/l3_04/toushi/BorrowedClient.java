package org.springframework.data.redis.laboratory.l3_04.toushi;

/**
 * 偷师对象 = Spring Data Redis {@code RedisConnectionUtils.releaseConnection}。
 *
 * <p>用 try-with-resources 包一层，业务方再也不会忘记 release：
 * <pre>{@code
 *   try (BorrowedClient bc = new BorrowedClient(provider)) {
 *       bc.client().call("/risk/check", body);
 *   }   // ← 自动 release，杜绝连接泄漏
 * }</pre>
 *
 * <p>这是把"忘记 returnObject"问题在编译期消灭的最佳实践。
 */
public class BorrowedClient implements AutoCloseable {

	private final ExternalApiClientProvider provider;
	private final ExternalApiClient client;

	public BorrowedClient(ExternalApiClientProvider provider) {
		this.provider = provider;
		this.client = provider.borrow();
	}

	public ExternalApiClient client() {
		return client;
	}

	@Override
	public void close() {
		provider.release(client);
	}
}
