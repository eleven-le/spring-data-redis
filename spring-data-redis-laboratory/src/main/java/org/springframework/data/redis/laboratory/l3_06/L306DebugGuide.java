package org.springframework.data.redis.laboratory.l3_06;

/**
 * <h3>L3-06 调试导航：从 RedisTemplate.opsForValue().set 到 Lettuce 原生命令的全链路</h3>
 *
 * <p>本章核心问题：<b>当业务代码写一句 {@code template.opsForValue().set("k","v")}
 * 的时候，到底有几层适配层把命令送到 Redis Server？</b></p>
 *
 * <p>这个类没有业务逻辑，作用是把推荐的断点顺序固化下来。在 IDEA 里把下面这些方法逐一打上断点，
 * 然后跑 {@link L306LettuceConnectionAdapterDemo}，你会清晰看到「层层适配」的全貌。</p>
 *
 * <h2>推荐断点顺序（自上而下）</h2>
 * <ol>
 * <li>业务入口：{@code StringRedisTemplate.opsForValue().set(key, value)}
 *     —— 业务代码唯一可见的层；</li>
 * <li>{@code AbstractOperations / DefaultValueOperations.set(...)}
 *     —— Operations 视图，仍然不知道底层是 Lettuce 还是 Jedis；</li>
 * <li>{@code RedisTemplate.execute(RedisCallback, boolean, boolean)}
 *     —— Template+Callback 模板入口，这里才开始拿 RedisConnection；</li>
 * <li>{@code RedisConnectionUtils.getConnection(RedisConnectionFactory, boolean)}
 *     —— 类比 {@code DataSourceUtils.getConnection}，处理事务绑定；</li>
 * <li>{@code RedisConnectionUtils.doGetConnection(...)}
 *     —— 真正决定走 ThreadLocal holder 还是 factory.getConnection；</li>
 * <li>{@code LettuceConnectionFactory.getConnection()}
 *     —— 适配器构造点，每次返回新的 LettuceConnection wrapper；</li>
 * <li>{@code LettuceConnectionFactory.doCreateLettuceConnection(...)}
 *     —— 模板方法钩子，注入 sharedConnection + connectionProvider；</li>
 * <li>{@code LettuceStringCommands.set(byte[], byte[])}
 *     —— Spring Data Redis 命令子门面（Set/Hash/List/Key 等都各自独立）；</li>
 * <li>{@code LettuceConnection.invoke()} → {@code LettuceConnection.doInvoke(...)}
 *     —— 这里把命令分发到「同步 / pipeline / transaction」三种语义；</li>
 * <li>{@code LettuceConnection.getAsyncConnection()}
 *     —— shared / dedicated 分流点（详见 L3-05）；</li>
 * <li>{@code LettuceInvoker.just(...)}
 *     —— 方法引用 + Synchronizer 的功能式调用入口；</li>
 * <li>Lettuce 原生：{@code RedisAsyncCommandsImpl.set(K, V)}
 *     —— 终于到了 Lettuce 原生 API，从这里开始走 Netty Channel；</li>
 * <li>异常路径：{@code LettuceExceptionConverter.convert(Exception)}
 *     —— Lettuce 异常 → Spring 的 DataAccessException；</li>
 * <li>资源释放：{@code RedisConnectionUtils.releaseConnection(...)} →
 *     {@code LettuceConnection.close()} → {@code LettuceConnection.reset()}
 *     —— RedisTemplate.execute finally 里走，注意 shared 不会真关物理连接。</li>
 * </ol>
 *
 * <h2>观察的变量</h2>
 * <ul>
 * <li>{@code LettuceConnection#asyncSharedConn} 是不是 null（共享连接）；</li>
 * <li>{@code LettuceConnection#asyncDedicatedConn} 是不是 null（专用连接）；</li>
 * <li>{@code LettuceConnection#connectionProvider} 的运行时类型
 *     （{@code ExceptionTranslatingConnectionProvider} → {@code StandaloneConnectionProvider}
 *     还是 {@code LettucePoolingConnectionProvider}）；</li>
 * <li>{@code LettuceInvoker#connection} 拿到的是 {@code RedisAsyncCommands}
 *     还是 {@code RedisClusterAsyncCommands}；</li>
 * <li>整条链路上同一次 set，被几个对象「转手」过 —— 这就是适配层的代价与价值。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-29
 */
public final class L306DebugGuide {

	private L306DebugGuide() {
	}

	public static void main(String[] args) {
		System.out.println("L3-06 debug guide: open this class Javadoc in IDEA, then follow the breakpoint order.");
	}
}
