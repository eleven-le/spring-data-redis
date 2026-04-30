package org.springframework.data.redis.laboratory.l3_06.toushi;

import java.util.HashMap;
import java.util.Map;

/**
 * <h3>L3-06 偷师：模拟一个底层「原生客户端」</h3>
 *
 * <p>把它类比成 {@code io.lettuce.core.RedisClient} +
 * {@code io.lettuce.core.api.sync.RedisCommands}：它的 API 非常贴近 Redis 协议，
 * 命名也来自客户端实现方，不是业务语义；如果业务直接依赖它，会出现以下问题：</p>
 * <ul>
 * <li>方法签名变化时业务代码大面积重写；</li>
 * <li>异常类型是「客户端异常」，业务要 catch {@code NativeRedisException}，跨数据源不一致；</li>
 * <li>切换到 Tair 时，业务要把 NativeRedisClient 全替换成 NativeTairClient。</li>
 * </ul>
 *
 * <p>这里用一个 {@link HashMap} 模拟内存 Redis，避免依赖真实 Redis；
 * 重点是看「适配器如何把这个 API 翻译成 {@link CacheConnection}」。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class NativeRedisClient {

	private final Map<String, String> kv = new HashMap<>();
	private boolean connected = false;

	public void connect() {
		this.connected = true;
		System.out.println("[NativeRedisClient] connect()");
	}

	/** 客户端原生命名风格：常常是 doGet / doSet 或 send / dispatch，业务读着累。 */
	public String dispatchGet(String key) {
		ensureConnected();
		return kv.get(key);
	}

	public void dispatchSet(String key, String value) {
		ensureConnected();
		kv.put(key, value);
	}

	public void dispatchDel(String key) {
		ensureConnected();
		kv.remove(key);
	}

	public void shutdown() {
		this.connected = false;
		System.out.println("[NativeRedisClient] shutdown()");
	}

	private void ensureConnected() {
		if (!connected) {
			throw new NativeRedisException("client not connected");
		}
	}

	/** 客户端原生异常：业务层 catch 这个就被绑死在具体客户端上。 */
	public static class NativeRedisException extends RuntimeException {
		public NativeRedisException(String message) {
			super(message);
		}
	}
}
