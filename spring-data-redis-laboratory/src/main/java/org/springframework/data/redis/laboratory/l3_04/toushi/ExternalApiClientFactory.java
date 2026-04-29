package org.springframework.data.redis.laboratory.l3_04.toushi;

/**
 * 偷师对象 = LettuceConnectionFactory。
 *
 * <p>把"创建客户端 + 生命周期 + 异常翻译"全收口在工厂层，业务方面对的是统一的 Provider。
 * 不要在业务代码里 {@code new OkHttpClient()}，更不要在每次请求时新建。
 */
public class ExternalApiClientFactory {

	private final String vendor;

	public ExternalApiClientFactory(String vendor) {
		this.vendor = vendor;
	}

	public ExternalApiClient create() {
		return new ExternalApiClient(vendor);
	}
}
