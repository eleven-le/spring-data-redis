package org.springframework.data.redis.laboratory.l3_04.toushi;

/**
 * 偷师对象 = Spring Data Redis 的 PoolException + ExceptionTranslator。
 *
 * <p>把 commons-pool2 的 NoSuchElementException / 内部 Exception 翻译成业务可识别的池异常，
 * 上层熔断/降级框架统一拦截。
 */
public class ExternalApiPoolException extends RuntimeException {

	public ExternalApiPoolException(String message, Throwable cause) {
		super(message, cause);
	}
}
