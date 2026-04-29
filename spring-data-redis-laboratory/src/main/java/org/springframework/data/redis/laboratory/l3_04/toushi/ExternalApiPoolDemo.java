package org.springframework.data.redis.laboratory.l3_04.toushi;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 偷师 Demo：把"风控 API"的客户端池跑一遍，验证 max-active 真的能保护下游。
 *
 * <p>跑完后你会看到：
 *  - 同一时刻只有 maxActive=4 个客户端在工作（哪怕有 50 个业务线程在排队）
 *  - 借不到的线程在 maxWait=200ms 内快速失败，绝不堆积 Tomcat
 *  - try-with-resources 杜绝忘记 release 的连接泄漏
 */
public class ExternalApiPoolDemo {

	public static void main(String[] args) throws InterruptedException {
		ExternalApiClientFactory factory = new ExternalApiClientFactory("Tongdun-RiskAPI");
		try (PooledExternalApiClientProvider provider =
				new PooledExternalApiClientProvider(factory, ExternalApiClientPoolConfig.forStrictVendor())) {

			int business = 50;
			CountDownLatch start = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(business);
			AtomicInteger ok = new AtomicInteger();
			AtomicInteger fail = new AtomicInteger();

			for (int i = 0; i < business; i++) {
				final int n = i;
				new Thread(() -> {
					try {
						start.await();
						try (BorrowedClient bc = new BorrowedClient(provider)) {
							bc.client().call("/risk/check", "uid=" + n);
							Thread.sleep(50);                       // 模拟下游 RTT
							ok.incrementAndGet();
						}
					} catch (ExternalApiPoolException e) {
						fail.incrementAndGet();                     // 借不到，立刻熔断/降级
					} catch (Exception ignore) {
					} finally {
						done.countDown();
					}
				}).start();
			}

			start.countDown();
			done.await();

			System.out.println("\n========= 偷师 Demo 结果 =========");
			System.out.println("业务线程数  = " + business);
			System.out.println("成功        = " + ok.get());
			System.out.println("快速失败    = " + fail.get() + "  ← max-wait 内借不到立刻失败，不拖垮上游");
			System.out.println("active/idle = " + provider.active() + "/" + provider.idle());
			System.out.println("\n关键观察：实际打到 Tongdun 的并发被池子卡在 maxActive=4 之内，下游被你保护了。");
		}
	}
}
