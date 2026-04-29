package org.springframework.data.redis.laboratory.l3_04.toushi;

/**
 * 偷师对象 = LettucePoolingConnectionProvider#discardIfNecessary + testOnBorrow。
 *
 * <p>在归还到池里之前，主动判定一次：客户端状态有没有被污染？
 * 例如：调用过返回 5xx、被对方限流（429）、TLS 会话失效等情况下，应该把客户端 destroy。
 */
public final class ClientHealthChecker {

	private ClientHealthChecker() {}

	public static boolean shouldDiscard(ExternalApiClient client, Throwable lastError) {
		if (!client.ping()) return true;
		if (lastError == null) return false;
		String msg = String.valueOf(lastError.getMessage());
		return msg.contains("429") || msg.contains("connection") || msg.contains("timeout");
	}
}
