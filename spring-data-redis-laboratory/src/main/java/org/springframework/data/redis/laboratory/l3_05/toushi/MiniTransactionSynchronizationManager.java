package org.springframework.data.redis.laboratory.l3_05.toushi;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 模拟 Spring Framework 的 {@code TransactionSynchronizationManager}。
 *
 * <p>真实 Spring 通过 ThreadLocal 把“当前线程绑定了哪些资源”保存起来：
 * <pre>{@code
 * TransactionSynchronizationManager.bindResource(factory, redisConnectionHolder)
 * TransactionSynchronizationManager.getResource(factory)
 * TransactionSynchronizationManager.unbindResource(factory)
 * }</pre>
 *
 * <p>为什么不用全局变量？因为 Web 容器里每个请求线程都可能正在处理不同用户、不同事务。
 * ThreadLocal 让一个 SessionCallback 内部多次调用 template 时能拿回同一个 connection wrapper，
 * 又不会串到别的请求线程。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public final class MiniTransactionSynchronizationManager {

	private static final ThreadLocal<Map<MiniConnectionFactory, MiniConnectionHolder>> RESOURCES =
			ThreadLocal.withInitial(IdentityHashMap::new);

	private MiniTransactionSynchronizationManager() {
	}

	public static MiniConnectionHolder getResource(MiniConnectionFactory factory) {
		return RESOURCES.get().get(factory);
	}

	public static void bindResource(MiniConnectionFactory factory, MiniConnectionHolder holder) {
		System.out.println("[tsm] bind holder to thread " + Thread.currentThread().getName());
		RESOURCES.get().put(factory, holder);
	}

	public static MiniConnectionHolder unbindResource(MiniConnectionFactory factory) {
		System.out.println("[tsm] unbind holder from thread " + Thread.currentThread().getName());
		MiniConnectionHolder holder = RESOURCES.get().remove(factory);
		if (RESOURCES.get().isEmpty()) {
			RESOURCES.remove();
		}
		return holder;
	}
}
