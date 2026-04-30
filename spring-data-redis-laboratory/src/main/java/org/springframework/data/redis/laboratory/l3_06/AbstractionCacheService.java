package org.springframework.data.redis.laboratory.l3_06;

import java.nio.charset.StandardCharsets;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisConnectionUtils;

/**
 * <h3>面向 RedisConnection 抽象的最小 CacheService（L3-06 实验 3 配套）</h3>
 *
 * <p>这个类被刻意拆出来做一等公民，是为了让你在 IDEA 里直观看到：<b>它的字段类型只到
 * {@link RedisConnectionFactory}，永远看不到 LettuceXxx</b>。这正是「面向接口编程」+
 * 「工厂抽象」共同带来的业务解耦。</p>
 *
 * <p>在 C 端高并发项目里，这种封装是底层基础设施的标配——业务团队只面向 CacheService，
 * SRE / 中间件团队负责 ConnectionFactory 的具体实现、监控、容量、熔断。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class AbstractionCacheService {

	private final RedisConnectionFactory connectionFactory;

	public AbstractionCacheService(RedisConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
	}

	public void put(String key, String value) {
		// 模板 / 回调思想的「手动版」：getConnection -> 业务 -> finally release
		RedisConnection connection = RedisConnectionUtils.getConnection(connectionFactory);
		try {
			connection.set(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
		} finally {
			RedisConnectionUtils.releaseConnection(connection, connectionFactory);
		}
	}

	public String get(String key) {
		RedisConnection connection = RedisConnectionUtils.getConnection(connectionFactory);
		try {
			byte[] raw = connection.get(key.getBytes(StandardCharsets.UTF_8));
			return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
		} finally {
			RedisConnectionUtils.releaseConnection(connection, connectionFactory);
		}
	}
}
