/*
 * Copyright 2013-2023 the original author or authors.
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

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.netty.channel.ChannelException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/**
 * Converts Lettuce Exceptions to {@link DataAccessException}s
 *
 * <h3>L3-06 源码导读：异常翻译 = SDR 给业务层的「统一异常宪法」</h3>
 *
 * <p>这是 Spring 风格的经典做法：<b>不让任何客户端异常泄漏到业务层</b>。
 * 类比 JDBC 中的 {@code SQLExceptionTranslator}，这里把：</p>
 * <ul>
 *   <li>{@code RedisCommandExecutionException} → {@code RedisSystemException}</li>
 *   <li>{@code RedisConnectionException} / Netty {@code ChannelException}
 *       → {@code RedisConnectionFailureException}</li>
 *   <li>{@code RedisCommandTimeoutException} / 标准 {@code TimeoutException}
 *       → {@code QueryTimeoutException}</li>
 *   <li>{@code RedisCommandInterruptedException} → {@code RedisSystemException}</li>
 * </ul>
 *
 * <p>这样业务层只需要 {@code catch (DataAccessException)}，无论是切换到 Jedis 还是 Lettuce，
 * 异常处理代码不需要改一行。L3-06 调试时可以在 {@link #convert} 上断点，
 * 触发 WRONGTYPE 等命令异常观察翻译过程。</p>
 *
 * @author Jennifer Hickey
 * @author Thomas Darimont
 * @author Mark Paluch
 */
public class LettuceExceptionConverter implements Converter<Exception, DataAccessException> {

	/*
	 * (non-Javadoc)
	 * @see org.springframework.core.convert.converter.Converter#convert(java.lang.Object)
	 */
	public DataAccessException convert(Exception ex) {

		if (ex instanceof ExecutionException || ex instanceof RedisCommandExecutionException) {

			if (ex.getCause() != ex && ex.getCause() instanceof Exception) {
				return convert((Exception) ex.getCause());
			}
			return new RedisSystemException("Error in execution", ex);
		}

		if (ex instanceof DataAccessException) {
			return (DataAccessException) ex;
		}

		if (ex instanceof RedisCommandInterruptedException) {
			return new RedisSystemException("Redis command interrupted", ex);
		}

		if (ex instanceof ChannelException || ex instanceof RedisConnectionException) {
			return new RedisConnectionFailureException("Redis connection failed", ex);
		}

		if (ex instanceof TimeoutException || ex instanceof RedisCommandTimeoutException) {
			return new QueryTimeoutException("Redis command timed out", ex);
		}

		if (ex instanceof RedisException) {
			return new RedisSystemException("Redis exception", ex);
		}

		return null;
	}
}
