package org.springframework.data.redis.laboratory.l3_03;

import java.util.concurrent.*;

/**
 * L3-03 实验⑤：手撸一个"乞丐版" Lettuce 多路复用模型
 * <p>
 * 【实验意图】
 * 完全不依赖 Redis、不依赖 Netty，用一个队列 + 一个 EventLoopThread
 * 把 "1 条共享 Channel + N 个业务线程 + 异步 Future" 的核心机制还原出来，
 * 让你彻底搞清楚两件事：
 * ① 多路复用不是魔法，本质是"队列 + Future + 单线程串行响应"
 * ② 阻塞命令为什么会卡死共享连接
 * <p>
 * 【模拟规则】
 * - 普通命令：每个 5ms 内"返回"
 * - BLPOP 命令：永远不返回，模拟生产事故
 * - 业务线程：sync 风格 future.get()，看似阻塞，实则等 EventLoopThread 完成
 * - EventLoopThread：唯一的"IO 线程"，按 FIFO 处理命令
 * <p>
 * 这就是 Lettuce 真实代码 CommandHandler#stack(队列) + EventLoop(IO 线程) 的极简对应。
 * <p>
 * 【运行无需 Redis，main 方法直接跑即可】
 *
 * @author leiyuhang
 * @since 2026-04-26
 */
public class MiniCommandMultiplexingSimulation {

    /**
     * 一条 Command + 它的 Future = 业务线程与 IO 线程之间的"信物"
     */
    static class MiniCommand {
        final String op;                    // GET / INCR / BLPOP
        final String key;
        final CompletableFuture<String> future = new CompletableFuture<>();

        MiniCommand(String op, String key) {
            this.op = op;
            this.key = key;
        }
    }

    /**
     * 模拟 Netty Channel + EventLoop：单线程顺序处理队列里的 Command
     */
    static class MiniChannel implements Runnable {
        private final BlockingQueue<MiniCommand> queue = new LinkedBlockingQueue<>();
        private volatile boolean running = true;

        public CompletableFuture<String> submit(MiniCommand cmd) {
            queue.offer(cmd);
            return cmd.future;
        }

        public void shutdown() {
            running = false;
        }

        @Override
        public void run() {
            while (running) {
                MiniCommand cmd;
                try {
                    cmd = queue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (cmd == null) continue;

                if ("BLPOP".equals(cmd.op)) {
                    // 模拟 Redis BLPOP 0 ：永远不回包
                    System.out.println("[event-loop] 收到 BLPOP，开始永久等待 ... 后续命令全部排队等死");
                    while (running) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    return;
                }

                // 普通命令：模拟网络往返 5ms
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                }

                cmd.future.complete(cmd.op + "(" + cmd.key + ")=ok");
            }
        }
    }

    public static void main(String[] args) throws Exception {

        //scenario_1();

       scenario_2();
    }

    private static void scenario_2() {
        // ───── 场景②：第一个就是 BLPOP → 整条 Channel 雪崩 ─────
        System.out.println("\n============ 场景② BLPOP 卡在前面，普通命令全部超时 ============");
        MiniChannel ch2 = new MiniChannel();
        Thread loop2 = new Thread(ch2, "event-loop-2");
        loop2.setDaemon(true);
        loop2.start();

        ch2.submit(new MiniCommand("BLPOP", "queue:empty")); // 投毒
        CompletableFuture<String> follower = ch2.submit(new MiniCommand("INCR", "stock:001"));

        try {
            String result = follower.get(2, TimeUnit.SECONDS);
            System.out.println("不可能走到这里，result = " + result);
        } catch (Exception ex) {
            System.out.println("❌ INCR 永远等不到，原因 = " + ex.getClass().getSimpleName());
            System.out.println("   (跟生产里 'Redis 怎么挂了？' 的报警长得一模一样)");
        }
        ch2.shutdown();

        System.out.println("\n💡 总结：");
        System.out.println("   - 多路复用 = 队列 + Future + 单线程顺序响应");
        System.out.println("   - 普通短命令很爽，因为它们立刻 complete future");
        System.out.println("   - 阻塞命令一上来，整条 Channel 等于'被一辆永不开动的车堵在最前面'");
    }

    private static void scenario_1() throws InterruptedException, ExecutionException, TimeoutException {
        // ───── 场景①：纯普通命令 → 多路复用一切顺滑 ─────
        System.out.println("============ 场景① 共享 Channel × 1000 普通命令 ============");
        MiniChannel ch1 = new MiniChannel();
        Thread loop1 = new Thread(ch1, "event-loop-1");
        loop1.start();

        long t0 = System.currentTimeMillis();
        CompletableFuture<?>[] futures = new CompletableFuture[1000];
        for (int i = 0; i < 1000; i++) {
            futures[i] = ch1.submit(new MiniCommand("INCR", "k" + i));
        }

        // 业务线程：sync 等所有结果
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

        long cost1 = System.currentTimeMillis() - t0;
        System.out.printf("✅ 1000 个普通命令完成，耗时 %d ms（IO 线程串行 5ms × 1000 ≈ 5s 内）%n", cost1);
        ch1.shutdown();
        loop1.join();
    }
}
