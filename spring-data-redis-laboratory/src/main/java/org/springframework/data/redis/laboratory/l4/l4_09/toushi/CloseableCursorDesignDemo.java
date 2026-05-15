package org.springframework.data.redis.laboratory.l4.l4_09.toushi;

import java.io.Closeable;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 偷师 Demo 2：CloseableCursor —— "游标 + 远程资源 + 必须 close"。
 * <p>
 * 灵感来源：SDR 的 {@code Cursor<T>}，hasNext/next 不是简单内存操作，可能触发远程 IO；
 * close 释放底层连接。
 * <p>
 * 业务真实场景：分页拉三方接口（订单中心、支付网关、推送系统）——
 * 每页一次远程调用，page token 在 server 端，client 必须 sticky；用完要释放配额 / 关闭 stream。
 */
public class CloseableCursorDesignDemo {

    /** 抽象接口：可关闭的游标。 */
    public interface CloseableCursor<T> extends Closeable {
        boolean hasNext();
        T next();
        long position();
        @Override void close();
    }

    /** 远程分页页对象。 */
    public static final class RemotePage<T> {
        public final java.util.List<T> items;
        public final String nextToken; // null 表示遍历结束
        public RemotePage(java.util.List<T> items, String nextToken) {
            this.items = items; this.nextToken = nextToken;
        }
    }

    /** 远程分页拉取器（业务里换成真实 HTTP / Feign 客户端）。 */
    public interface RemotePageFetcher<T> extends Function<String, RemotePage<T>> { }

    /**
     * 标准实现：内部维护当前页 + 当前 index + 下一页 token；用 AtomicBoolean 保证 close 幂等。
     */
    public static final class RemotePageCursor<T> implements CloseableCursor<T> {
        private final RemotePageFetcher<T> fetcher;
        private final long maxItems;
        private RemotePage<T> currentPage;
        private int idxInPage = 0;
        private long position = 0;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        public RemotePageCursor(RemotePageFetcher<T> fetcher, long maxItems) {
            this.fetcher = fetcher;
            this.maxItems = maxItems;
            this.currentPage = fetcher.apply(null); // 第一页
        }

        @Override
        public boolean hasNext() {
            if (closed.get()) return false;
            if (position >= maxItems) return false;
            // 当前页还没消费完
            if (currentPage != null && idxInPage < currentPage.items.size()) return true;
            // 当前页消费完且没有下一页
            if (currentPage == null || currentPage.nextToken == null) return false;
            // 拉下一页
            currentPage = fetcher.apply(currentPage.nextToken);
            idxInPage = 0;
            return currentPage != null && !currentPage.items.isEmpty();
        }

        @Override
        public T next() {
            if (closed.get()) throw new IllegalStateException("cursor closed");
            if (!hasNext()) throw new NoSuchElementException();
            T item = currentPage.items.get(idxInPage++);
            position++;
            return item;
        }

        @Override
        public long position() { return position; }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // 真实业务这里释放配额、关 stream、归还连接
                currentPage = null;
            }
        }
    }

    public static void main(String[] args) {
        // 模拟一个能返回 3 页（每页 4 条）数据的 fetcher
        RemotePageFetcher<String> fetcher = token -> {
            int pageNo = token == null ? 0 : Integer.parseInt(token);
            int total = 3;
            if (pageNo >= total) return new RemotePage<>(java.util.List.of(), null);
            java.util.List<String> items = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) items.add("p" + pageNo + "-i" + i);
            String next = (pageNo + 1 < total) ? String.valueOf(pageNo + 1) : null;
            return new RemotePage<>(items, next);
        };

        try (RemotePageCursor<String> cursor = new RemotePageCursor<>(fetcher, 100)) {
            while (cursor.hasNext()) {
                System.out.println("got: " + cursor.next() + " (pos=" + cursor.position() + ")");
            }
        }
        // 偷师对照：SDR Cursor.close 释放 sticky 连接；这里 close 释放远程资源 / 配额。
    }
}
