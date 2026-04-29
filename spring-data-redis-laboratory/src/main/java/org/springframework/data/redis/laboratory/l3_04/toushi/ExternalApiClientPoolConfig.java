package org.springframework.data.redis.laboratory.l3_04.toushi;

/**
 * 偷师对象 = LettucePoolingClientConfiguration + GenericObjectPoolConfig。
 *
 * <p>把所有"池化策略"集中到一个对象，业务方按 vendor 给不同配置：
 * - 风控 API：maxActive 小（贵+被严格限流）+ maxWait 短（接口降级）
 * - 地图 API：maxActive 中等 + maxWait 适中
 * - 内部低敏 API：可以放大
 */
public class ExternalApiClientPoolConfig {

	public final int maxActive;
	public final int maxIdle;
	public final int minIdle;
	public final long maxWaitMillis;
	public final boolean testOnBorrow;

	private ExternalApiClientPoolConfig(int maxActive, int maxIdle, int minIdle,
										long maxWaitMillis, boolean testOnBorrow) {
		this.maxActive = maxActive;
		this.maxIdle = maxIdle;
		this.minIdle = minIdle;
		this.maxWaitMillis = maxWaitMillis;
		this.testOnBorrow = testOnBorrow;
	}

	public static ExternalApiClientPoolConfig forStrictVendor() {
		return new ExternalApiClientPoolConfig(/*maxActive*/ 4, /*maxIdle*/ 4, /*minIdle*/ 1,
				/*maxWaitMillis*/ 200, /*testOnBorrow*/ true);
	}

	public static ExternalApiClientPoolConfig forNormalVendor() {
		return new ExternalApiClientPoolConfig(8, 8, 2, 500, true);
	}
}
