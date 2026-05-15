package org.springframework.data.redis.laboratory.l4.l4_12.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 章节内统一的彩色日志（其实只是带线程名的格式化打印），便于在控制台
 * 直观区分 Pub/Sub 不同线程：
 * - lettuce-pubSub-x：Lettuce 收消息的 EventLoop 线程
 * - container-N：默认 SimpleAsyncTaskExecutor 给 listener 派发用的线程
 * - biz-pool-N：demo04 自定义业务线程池
 */
public final class LogPrinter {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void print(String tag, String message) {
        System.out.printf("[%s][%s][%s] %s%n",
                F.format(LocalTime.now()), Thread.currentThread().getName(), tag, message);
    }

    private LogPrinter() {
    }
}
