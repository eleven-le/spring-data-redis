/*
 * Copyright 2017-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.connection.lettuce;

import io.lettuce.core.KeyValue;
import io.lettuce.core.LPosArgs;
import io.lettuce.core.api.async.RedisListAsyncCommands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.redis.connection.RedisListCommands;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * @author Christoph Strobl
 * @author Mark Paluch
 * @author dengliming
 * @since 2.0
 */
class LettuceListCommands implements RedisListCommands {

	private final LettuceConnection connection;

	LettuceListCommands(LettuceConnection connection) {
		this.connection = connection;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#rPush(byte[], byte[][])
	 */
	@Override
	public Long rPush(byte[] key, byte[]... values) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::rpush, key, values);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#l{lPos(byte[], byte[], java.lang.Integer, java.lang.Integer)
	 */
	@Override
	public List<Long> lPos(byte[] key, byte[] element, @Nullable Integer rank, @Nullable Integer count) {

		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(element, "Element must not be null!");

		LPosArgs args = new LPosArgs();
		if (rank != null) {
			args.rank(rank);
		}

		if (count != null) {
			return connection.invoke().just(RedisListAsyncCommands::lpos, key, element, count, args);
		}

		return connection.invoke().from(RedisListAsyncCommands::lpos, key, element, args)
				.getOrElse(Collections::singletonList, Collections::emptyList);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lPush(byte[], byte[][])
	 */
	@Override
	public Long lPush(byte[] key, byte[]... values) {

		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(values, "Values must not be null!");
		Assert.noNullElements(values, "Values must not contain null elements!");

		return connection.invoke().just(RedisListAsyncCommands::lpush, key, values);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#rPushX(byte[], byte[])
	 */
	@Override
	public Long rPushX(byte[] key, byte[] value) {

		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(value, "Value must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::rpushx, key, value);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lPushX(byte[], byte[])
	 */
	@Override
	public Long lPushX(byte[] key, byte[] value) {

		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(value, "Value must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::lpushx, key, value);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lLen(byte[])
	 */
	@Override
	public Long lLen(byte[] key) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::llen, key);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lRange(byte[], long, long)
	 */
	@Override
	public List<byte[]> lRange(byte[] key, long start, long end) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::lrange, key, start, end);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lTrim(byte[], long, long)
	 */
	@Override
	public void lTrim(byte[] key, long start, long end) {

		Assert.notNull(key, "Key must not be null!");

		connection.invokeStatus().just(RedisListAsyncCommands::ltrim, key, start, end);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lIndex(byte[], long)
	 */
	@Override
	public byte[] lIndex(byte[] key, long index) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::lindex, key, index);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lInsert(byte[], org.springframework.data.redis.connection.RedisListCommands.Position, byte[], byte[])
	 */
	@Override
	public Long lInsert(byte[] key, Position where, byte[] pivot, byte[] value) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::linsert, key, LettuceConverters.toBoolean(where), pivot,
				value);
	}

	/* 
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lMove(byte[], byte[], org.springframework.data.redis.connection.RedisListCommands.Direction, org.springframework.data.redis.connection.RedisListCommands.Direction)
	 */
	@Override
	public byte[] lMove(byte[] sourceKey, byte[] destinationKey, Direction from, Direction to) {

		Assert.notNull(sourceKey, "Source key must not be null!");
		Assert.notNull(destinationKey, "Destination key must not be null!");
		Assert.notNull(from, "From direction must not be null!");
		Assert.notNull(to, "To direction must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::lmove, sourceKey, destinationKey,
				LettuceConverters.toLmoveArgs(from, to));

	}

	/* 
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#bLMove(byte[], byte[], org.springframework.data.redis.connection.RedisListCommands.Direction, org.springframework.data.redis.connection.RedisListCommands.Direction, double)
	 */
	@Override
	public byte[] bLMove(byte[] sourceKey, byte[] destinationKey, Direction from, Direction to, double timeout) {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 阻塞命令 · BLMOVE 显式走 dedicated</h3>
		 *
		 * <p><b>调用方</b>：订单队列、延迟队列、任务转移队列等业务通过
		 * {@code RedisListCommands#bLMove(...)} 进入。</p>
		 *
		 * <p><b>触发条件</b>：{@code BLMOVE} 可能阻塞等待源队列元素，连接占用时间远大于普通 GET/SET。</p>
		 *
		 * <p><b>做出的决定</b>：这里没有调用默认 {@code connection.invoke()}，而是显式传入
		 * {@code connection.getAsyncDedicatedConnection()}，把阻塞等待放到专用连接上。</p>
		 *
		 * <p><b>跳过它会怎样</b>：一个空订单队列的长轮询会卡住共享连接，商品详情、库存查询、
		 * 优惠券状态查询这些短命令在客户端侧排队超时，Redis Server CPU 可能仍然很低。
		 * ===== END ===== */
		Assert.notNull(sourceKey, "Source key must not be null!");
		Assert.notNull(destinationKey, "Destination key must not be null!");
		Assert.notNull(from, "From direction must not be null!");
		Assert.notNull(to, "To direction must not be null!");

		return connection.invoke(connection.getAsyncDedicatedConnection()).just(RedisListAsyncCommands::blmove, sourceKey,
				destinationKey, LettuceConverters.toLmoveArgs(from, to), timeout);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lSet(byte[], long, byte[])
	 */
	@Override
	public void lSet(byte[] key, long index, byte[] value) {

		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(value, "Value must not be null!");

		connection.invokeStatus().just(RedisListAsyncCommands::lset, key, index, value);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lRem(byte[], long, byte[])
	 */
	@Override
	public Long lRem(byte[] key, long count, byte[] value) {

		Assert.notNull(key, "Key must not be null!");
		Assert.notNull(value, "Value must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::lrem, key, count, value);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lPop(byte[])
	 */
	@Override
	public byte[] lPop(byte[] key) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::lpop, key);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#lPop(byte[], long)
	 */
	@Override
	public List<byte[]> lPop(byte[] key, long count) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::lpop, key, count);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#rPop(byte[])
	 */
	@Override
	public byte[] rPop(byte[] key) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::rpop, key);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#rPop(byte[], long)
	 */
	@Override
	public List<byte[]> rPop(byte[] key, long count) {

		Assert.notNull(key, "Key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::rpop, key, count);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#bLPop(int, byte[][])
	 */
	@Override
	public List<byte[]> bLPop(int timeout, byte[]... keys) {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 阻塞命令 · BLPOP 显式走 dedicated</h3>
		 *
		 * <p><b>调用方</b>：订单队列消费、异步发券队列、库存广播补偿队列等基于 Redis List
		 * 的长轮询消费逻辑。</p>
		 *
		 * <p><b>触发条件</b>：{@code BLPOP timeout key...} 在队列为空时会阻塞等待。
		 * timeout 为 0 时可能无限期占住连接。</p>
		 *
		 * <p><b>做出的决定</b>：直接调用 {@code connection.invoke(connection.getAsyncDedicatedConnection())}，
		 * 不允许 BLPOP 走 {@code asyncSharedConn}。这就是 L3-05 阻塞命令触发
		 * {@code asyncDedicatedConn} 的最直观源码入口。</p>
		 *
		 * <p><b>跳过它会怎样</b>：共享 Netty Channel 的队头被 BLPOP 占住，后续短命令即使 Redis
		 * 端可以立刻处理，也会在客户端连接通道上排队，线上表现为大面积 Redis 调用超时。
		 * ===== END ===== */
		Assert.notNull(keys, "Key must not be null!");
		Assert.noNullElements(keys, "Keys must not contain null elements!");

		return connection.invoke(connection.getAsyncDedicatedConnection())
				.from(RedisListAsyncCommands::blpop, timeout, keys).get(LettuceListCommands::toBytesList);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#bRPop(int, byte[][])
	 */
	@Override
	public List<byte[]> bRPop(int timeout, byte[]... keys) {

		/* ===== L3-05 讲解注释 BY 军火库 =====
		 * <h3>🔥 阻塞命令 · BRPOP 显式走 dedicated</h3>
		 *
		 * <p><b>调用方</b>：和 BLPOP 类似，常见于订单队列、发券队列、榜单异步刷新队列。</p>
		 *
		 * <p><b>触发条件</b>：{@code BRPOP} 会在队列为空时阻塞等待尾部元素。</p>
		 *
		 * <p><b>做出的决定</b>：同样显式传入 {@code getAsyncDedicatedConnection()}，
		 * 让阻塞语义与普通商品/库存短命令隔离。</p>
		 *
		 * <p><b>跳过它会怎样</b>：某个消费者线程的长等待会把共享连接变成长连接阻塞点，
		 * 造成“Redis CPU 不高但调用全慢”的典型误诊现场。
		 * ===== END ===== */
		Assert.notNull(keys, "Key must not be null!");
		Assert.noNullElements(keys, "Keys must not contain null elements!");

		return connection.invoke(connection.getAsyncDedicatedConnection())
				.from(RedisListAsyncCommands::brpop, timeout, keys).get(LettuceListCommands::toBytesList);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#rPopLPush(byte[], byte[])
	 */
	@Override
	public byte[] rPopLPush(byte[] srcKey, byte[] dstKey) {

		Assert.notNull(srcKey, "Source key must not be null!");
		Assert.notNull(dstKey, "Destination key must not be null!");

		return connection.invoke().just(RedisListAsyncCommands::rpoplpush, srcKey, dstKey);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.redis.connection.RedisListCommands#bRPopLPush(int, byte[], byte[])
	 */
	@Override
	public byte[] bRPopLPush(int timeout, byte[] srcKey, byte[] dstKey) {

		Assert.notNull(srcKey, "Source key must not be null!");
		Assert.notNull(dstKey, "Destination key must not be null!");

		return connection.invoke(connection.getAsyncDedicatedConnection()).just(RedisListAsyncCommands::brpoplpush, timeout,
				srcKey, dstKey);
	}

	private static List<byte[]> toBytesList(KeyValue<byte[], byte[]> source) {

		List<byte[]> list = new ArrayList<>(2);
		list.add(source.getKey());
		list.add(source.getValue());

		return list;
	}
}
