package org.springframework.data.redis.laboratory.l3_04.toushi;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 偷师对象 = StatefulRedisConnection。
 *
 * <p>这是真正向第三方 API 发请求的客户端，含 keep-alive、TLS 握手缓存等"昂贵"状态，
 * 不能频繁 new。
 */
public class ExternalApiClient {

	private static final AtomicInteger ID_GEN = new AtomicInteger();

	private final int id = ID_GEN.incrementAndGet();
	private final String vendor;
	private volatile boolean healthy = true;

	public ExternalApiClient(String vendor) {
		this.vendor = vendor;
		// 这里在生产代码里会做：建连接、TLS 握手、auth、配置 timeout 等
	}

	public String call(String api, String body) {
		if (!healthy) {
			throw new IllegalStateException("client #" + id + " is broken");
		}
		return "[client#" + id + "/" + vendor + "] -> " + api;
	}

	public boolean ping() {
		return healthy;
	}

	public void markBroken() {
		this.healthy = false;
	}

	public void close() {
		this.healthy = false;
	}

	public int getId() {
		return id;
	}

	@Override
	public String toString() {
		return "ExternalApiClient#" + id + "/" + vendor;
	}
}
