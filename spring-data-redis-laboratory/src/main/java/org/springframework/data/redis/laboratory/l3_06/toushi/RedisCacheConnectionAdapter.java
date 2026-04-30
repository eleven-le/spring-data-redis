package org.springframework.data.redis.laboratory.l3_06.toushi;

/**
 * <h3>L3-06 偷师：Adapter 模式的核心实现 —— LettuceConnection 的同款思想</h3>
 *
 * <p>这个类对应 Spring Data Redis 中的
 * {@link org.springframework.data.redis.connection.lettuce.LettuceConnection}：</p>
 * <ul>
 * <li><b>对上</b>：实现统一的 {@link CacheConnection} 抽象（业务唯一可见的接口）；</li>
 * <li><b>对下</b>：组合一个具体的 {@link NativeRedisClient}（被适配者），
 *     并把统一接口的方法翻译成它的 dispatchGet / dispatchSet 调用；</li>
 * <li><b>异常翻译</b>：把 {@code NativeRedisClient.NativeRedisException}
 *     转成业务可读的统一异常，业务层无需感知客户端类型。</li>
 * </ul>
 *
 * <h4>偷师要点（迁移到 C 端基础设施）</h4>
 * <ol>
 * <li>团队内部约定一个面向业务语义的 {@code XxxConnection} 接口；</li>
 * <li>每接入一种新底层（Redis / Tair / 自研 KV），写一个 Adapter 实现；</li>
 * <li>异常一律翻译成内部统一异常，业务统一 catch；</li>
 * <li>对应 Spring 思想：面向接口编程 + 组合优于继承 + Adapter + Exception Translation。</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class RedisCacheConnectionAdapter implements CacheConnection {

	private final NativeRedisClient nativeClient;
	private boolean closed = false;

	public RedisCacheConnectionAdapter(NativeRedisClient nativeClient) {
		this.nativeClient = nativeClient;
	}

	@Override
	public void set(String key, String value) {
		ensureOpen();
		try {
			nativeClient.dispatchSet(key, value);
		} catch (NativeRedisClient.NativeRedisException ex) {
			throw translate(ex);
		}
	}

	@Override
	public String get(String key) {
		ensureOpen();
		try {
			return nativeClient.dispatchGet(key);
		} catch (NativeRedisClient.NativeRedisException ex) {
			throw translate(ex);
		}
	}

	@Override
	public void del(String key) {
		ensureOpen();
		try {
			nativeClient.dispatchDel(key);
		} catch (NativeRedisClient.NativeRedisException ex) {
			throw translate(ex);
		}
	}

	@Override
	public void close() {
		// 这里只关闭 wrapper，类比 LettuceConnection.close 不会真关 shared connection
		this.closed = true;
	}

	private void ensureOpen() {
		if (closed) {
			throw new IllegalStateException("CacheConnection already closed");
		}
	}

	/** 异常翻译 —— 类比 {@code LettuceExceptionConverter}。 */
	private RuntimeException translate(NativeRedisClient.NativeRedisException ex) {
		return new IllegalStateException("CacheConnection access failure: " + ex.getMessage(), ex);
	}
}
