package org.springframework.data.redis.laboratory.l3_05.toushi;

/**
 * 模拟 {@code RedisConnectionHolder}。
 *
 * <p>Holder 的意义是把资源对象和“当前线程正在使用它”的状态放在一起。
 * Spring Framework 中 {@code ResourceHolderSupport} 还提供 requested/released、rollback-only、
 * timeout 等通用资源管理能力。</p>
 *
 * <p>本示例保留最小模型：持有一个 {@link MiniConnection}，并用引用计数模拟嵌套 callback。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class MiniConnectionHolder {

	private final MiniConnection connection;
	private int referenceCount;

	public MiniConnectionHolder(MiniConnection connection) {
		this.connection = connection;
	}

	public MiniConnection getConnection() {
		return connection;
	}

	public void requested() {
		referenceCount++;
		System.out.println("[holder] requested, ref=" + referenceCount);
	}

	public void released() {
		referenceCount--;
		System.out.println("[holder] released, ref=" + referenceCount);
	}

	public boolean isOpen() {
		return referenceCount > 0;
	}
}
