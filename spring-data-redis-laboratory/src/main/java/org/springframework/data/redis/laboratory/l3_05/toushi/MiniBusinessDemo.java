package org.springframework.data.redis.laboratory.l3_05.toushi;

/**
 * 可运行偷师 Demo：用日志模拟 shared / dedicated connection 设计。
 *
 * <p>运行后重点看四件事：
 * <ul>
 * <li>普通命令只使用 shared connection；</li>
 * <li>pipeline / transaction / blocking command 第一次触发 dedicated lazy initialization；</li>
 * <li>Session 范围内会通过 ThreadLocal holder 复用同一个 wrapper；</li>
 * <li>Template 的 finally 会 close wrapper，并释放 dedicated connection。</li>
 * </ul>
 *
 * <p>这套设计可以迁移到 C 端业务系统：例如把“普通快速查询”走共享通道，把“长耗时批处理 /
 * 独占上下文 / 阻塞等待”隔离到专用资源池，避免主链路被慢操作拖垮。</p>
 *
 * @author leilei
 * @since 2026-04-29
 */
public class MiniBusinessDemo {

	public static void main(String[] args) {

		MiniRedisTemplate template = new MiniRedisTemplate(new MiniConnectionFactory());

		System.out.println("\n=== 1. 普通命令：走 shared connection ===");
		template.execute(connection -> {
			connection.set("home:feed:u1001", "v1");
			connection.get("home:feed:u1001");
			return null;
		});

		System.out.println("\n=== 2. pipeline：打开时 lazy borrow dedicated connection ===");
		template.executePipelined(connection -> {
			connection.set("coupon:u1001", "1");
			connection.set("coupon:u1002", "1");
			connection.get("coupon:u1001");
			return null;
		});

		System.out.println("\n=== 3. transaction：ThreadLocal holder + dedicated connection ===");
		template.executeSession(connection -> {
			connection.multi();
			template.execute(inner -> {
				inner.set("stock:item:100", "99");
				inner.get("stock:item:100");
				return null;
			});
			connection.exec();
			return null;
		});

		System.out.println("\n=== 4. blocking command：显式隔离到 dedicated connection ===");
		template.execute(connection -> {
			connection.blpop("order:queue", 3);
			return null;
		});
	}
}
