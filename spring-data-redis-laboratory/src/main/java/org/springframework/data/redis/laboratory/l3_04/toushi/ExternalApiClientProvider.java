package org.springframework.data.redis.laboratory.l3_04.toushi;

/**
 * 偷师对象 = LettuceConnectionProvider 接口。
 *
 * <p>Strategy/Provider 模式：业务方不关心客户端是单例共享 / 池化借出 / 每次新建，
 * 只调 {@link #borrow()} + {@link #release(ExternalApiClient)}。
 */
public interface ExternalApiClientProvider {

	ExternalApiClient borrow();

	void release(ExternalApiClient client);
}
