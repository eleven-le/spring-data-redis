package org.springframework.data.redis.laboratory.l3_06.toushi;

/**
 * <h3>L3-06 偷师 Demo：用最小代码复现 LettuceConnection 的 Adapter 模式</h3>
 *
 * <p>这个 demo 不连真实 Redis、不依赖 Spring。我们用最朴素的 Java 类把
 * 「适配器」「工厂」「面向接口」「异常翻译」四个思想压缩到 5 个文件，
 * 让你不被 Spring 的复杂性干扰，看清模式本身。</p>
 *
 * <h4>对照表（偷师 → 源码）</h4>
 * <table border="1">
 * <tr><th>本 demo</th><th>Spring Data Redis 源码</th></tr>
 * <tr><td>{@link CacheConnection}</td><td>{@code RedisConnection}</td></tr>
 * <tr><td>{@link CacheConnectionFactory}</td><td>{@code RedisConnectionFactory}</td></tr>
 * <tr><td>{@link NativeRedisClient}</td><td>{@code io.lettuce.core.RedisClient} / RedisCommands</td></tr>
 * <tr><td>{@link RedisCacheConnectionAdapter}</td><td>{@code LettuceConnection}</td></tr>
 * <tr><td>{@link RedisCacheConnectionFactory}</td><td>{@code LettuceConnectionFactory}</td></tr>
 * <tr><td>{@link BusinessCacheService}</td><td>业务层（CacheService / Repository）</td></tr>
 * </table>
 *
 * <h4>偷师四步法（这一节真正的目的）</h4>
 * <ol>
 * <li><b>识别模式</b>：LettuceConnection 是 Adapter 模式 + Facade（多个 Lettuce 命令族被组合成一个统一门面）；</li>
 * <li><b>理解意图</b>：让上层永远只面向 RedisConnection，不被任何特定客户端绑死；</li>
 * <li><b>抽象结构</b>：{@code Abstraction（接口）+ Adapter（适配类）+ Adaptee（具体客户端）+ Factory（工厂）}；</li>
 * <li><b>迁移应用</b>：在自己的项目中，凡是「上层依赖某种基础能力，但底层有多种实现」的场景都可以套这个结构。
 *     例子：消息队列（Kafka / RocketMQ / Pulsar）、对象存储（OSS / S3 / 自研）、配置中心（Apollo / Nacos）。</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class ToushiAdapterPatternDemo {

	public static void main(String[] args) {

		// 1. 工厂的具体实现可以随意替换：今天是 Redis，明天可以是 Tair / Memcached
		CacheConnectionFactory factory = new RedisCacheConnectionFactory();

		// 2. 业务层只依赖抽象工厂，不感知 RedisCacheConnectionFactory
		BusinessCacheService cacheService = new BusinessCacheService(factory);

		cacheService.cacheUser("u1001", "{\"name\":\"Tom\"}");
		System.out.println("loadUser = " + cacheService.loadUser("u1001"));

		// 3. 资源销毁交给工厂，业务也不需要关心
		factory.shutdown();

		System.out.println("\n偷师结论：");
		System.out.println(" - LettuceConnection ≈ RedisCacheConnectionAdapter");
		System.out.println(" - 业务层只看到 CacheConnection / CacheConnectionFactory");
		System.out.println(" - 切换底层只动 Factory 实现，业务零改动");
		System.out.println(" - 这就是 Spring Data Redis 真正想让我们偷师的结构");
	}
}
