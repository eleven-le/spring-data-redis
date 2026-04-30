package org.springframework.data.redis.laboratory.l3_08.toushi.decorator_for_pooling;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * <h3>🥷 偷师 demo:用装饰器把"池化"剥成正交能力</h3>
 *
 * <p>对照 SDR:{@code LettucePoolingConnectionProvider} 装饰
 * {@code StandaloneConnectionProvider}。</p>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class MiniPoolingDecoratorDemo {

    /** 顶层接口 */
    interface ConnProvider {
        Conn get();
        void release(Conn conn);
    }

    static class Conn {
        final String id;
        Conn(String id) { this.id = id; }
        @Override public String toString() { return "Conn(" + id + ")"; }
    }

    /** 底层:每次新建,不池化 */
    static class StandaloneProvider implements ConnProvider {
        int seq = 0;
        @Override public Conn get() {
            Conn c = new Conn("std-" + (++seq));
            System.out.println("  [Standalone] create " + c);
            return c;
        }
        @Override public void release(Conn conn) {
            System.out.println("  [Standalone] close " + conn);
        }
    }

    /** 装饰器:在底层之上加池化 */
    static class PoolingProvider implements ConnProvider {
        private final ConnProvider delegate;
        private final Deque<Conn> idle = new ArrayDeque<>();

        PoolingProvider(ConnProvider delegate) { this.delegate = delegate; }

        @Override public Conn get() {
            Conn c = idle.isEmpty() ? delegate.get() : idle.poll();
            System.out.println("  [Pooling] borrow " + c + " (idle=" + idle.size() + ")");
            return c;
        }

        @Override public void release(Conn conn) {
            idle.offer(conn);
            System.out.println("  [Pooling] return " + conn + " to idle (idle=" + idle.size() + ")");
            // 真正销毁的时机交给上游 close,这里不调 delegate.release
        }
    }

    public static void main(String[] args) {

        System.out.println("🚦 STEP-1: 裸用 Standalone(无池) → 借两次会建两条");
        ConnProvider raw = new StandaloneProvider();
        Conn a = raw.get();
        raw.release(a);
        Conn b = raw.get();
        raw.release(b);

        System.out.println("\n🚦 STEP-2: 装饰一层 PoolingProvider → 借两次只建一条");
        ConnProvider pooled = new PoolingProvider(new StandaloneProvider());
        Conn c = pooled.get();
        pooled.release(c);
        Conn d = pooled.get();          // 复用!
        pooled.release(d);
        System.out.println("\n💡 c == d ? " + (c == d) + " — 装饰器把池化能力正交叠加");
    }
}
