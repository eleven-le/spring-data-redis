package org.springframework.data.redis.laboratory.l3_05.support;

/**
 * L3-05 调试导航：专门给 IDEA 断点阅读用。
 *
 * <p>本类没有业务逻辑，作用是把本章最值得跟的断点顺序固定下来。你可以先打开这个类，
 * 再按下面顺序在 Spring Data Redis 源码中打断点。</p>
 *
 * <h2>推荐断点顺序</h2>
 * <ol>
 * <li>{@code RedisTemplate.execute(RedisCallback, boolean, boolean)}
 * <p>所有普通 RedisTemplate 操作和部分 pipeline 路径的模板入口。</p></li>
 * <li>{@code RedisConnectionUtils.getConnection(RedisConnectionFactory, boolean)}
 * <p>连接获取入口，本身不决定 shared/dedicated，只负责进入 doGetConnection。</p></li>
 * <li>{@code RedisConnectionUtils.doGetConnection(RedisConnectionFactory, boolean, boolean, boolean)}
 * <p>观察 {@code TransactionSynchronizationManager.getResource(factory)} 是否已有
 * {@code RedisConnectionHolder}。</p></li>
 * <li>{@code LettuceConnectionFactory.getConnection()}
 * <p>每次返回新的 {@code LettuceConnection} wrapper，同时注入 shared connection 和 provider。</p></li>
 * <li>{@code LettuceConnection.<init>(StatefulConnection, LettuceConnectionProvider, long, int)}
 * <p>观察 {@code asyncSharedConn} 构造时是否为 null。</p></li>
 * <li>{@code LettuceConnection.openPipeline()}
 * <p>当前 2.7.18 版本会调用 {@code getOrCreateDedicatedConnection()}，pipeline 打开时就触发 dedicated。</p></li>
 * <li>{@code LettuceConnection.getAsyncConnection()}
 * <p>普通异步命令的分流点：queueing/pipelined 走 dedicated，否则优先 shared。</p></li>
 * <li>{@code LettuceConnection.getAsyncDedicatedConnection()}
 * <p>当前版本真实存在的方法名。它先校验连接未关闭，再拿 dedicated native connection 的 async commands。</p></li>
 * <li>{@code LettuceConnection.doGetAsyncDedicatedConnection()}
 * <p>真正向 {@code LettuceConnectionProvider.getConnection(StatefulConnection.class)} 借连接的位置。</p></li>
 * <li>{@code LettuceConnection.close()}
 * <p>wrapper 关闭入口；普通 shared native connection 不在这里关闭，dedicated connection 会在 reset 中释放。</p></li>
 * <li>{@code RedisConnectionUtils.releaseConnection(RedisConnection, RedisConnectionFactory)}
 * <p>RedisTemplate finally 中释放连接；如果 thread-bound holder 存在，释放可能推迟到 unbind/事务完成。</p></li>
 * </ol>
 *
 * <h2>当前源码方法名对照</h2>
 * <ul>
 * <li>有些资料会说 {@code getAsyncDedicatedRedisCommands()}，它在 2.7.18 中是 private 辅助方法；</li>
 * <li>本章主要跟 {@code getAsyncDedicatedConnection()}、{@code doGetAsyncDedicatedConnection()}、
 * {@code getOrCreateDedicatedConnection()}；</li>
 * <li>SCAN 游标要跟 {@code RedisTemplate.executeWithStickyConnection(...)} 和
 * {@code LettuceKeyCommands.doScan(...)}，不要误以为所有 scan 都会创建 {@code asyncDedicatedConn}。</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-29
 */
public final class L305DebugGuide {

	private L305DebugGuide() {
	}

	public static void main(String[] args) {
		System.out.println("L3-05 debug guide: open this class Javadoc in IDEA and follow the breakpoint order.");
	}
}
