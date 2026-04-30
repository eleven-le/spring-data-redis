package org.springframework.data.redis.laboratory.l3_08.toushi.spi_provider_hierarchy;

/**
 * <h3>🥷 偷师 demo:用 SDR 同款 Provider Hierarchy 做"多渠道通知发送器"</h3>
 *
 * <p>对照 SDR:</p>
 * <ul>
 *   <li>{@code NotifyProvider}              ↔ {@code LettuceConnectionProvider}</li>
 *   <li>{@code SmsProvider}                  ↔ {@code StandaloneConnectionProvider}</li>
 *   <li>{@code RetryDecoratorProvider}       ↔ {@code LettucePoolingConnectionProvider}</li>
 *   <li>{@code TargetAware}                  ↔ 同名接口</li>
 * </ul>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class MiniNotifyProviderDemo {

    /** 顶层接口 */
    interface NotifyProvider {
        void send(String to, String content);
    }

    /** 标记接口:能否定向到指定渠道 */
    interface TargetAware {
        String channel();
    }

    /** 短信实现 */
    static class SmsProvider implements NotifyProvider, TargetAware {
        @Override public void send(String to, String content) {
            System.out.println("  [SMS] → " + to + " : " + content);
        }
        @Override public String channel() { return "SMS"; }
    }

    /** 装饰器:重试 */
    static class RetryDecoratorProvider implements NotifyProvider, TargetAware {
        private final NotifyProvider delegate;
        private final int retries;
        RetryDecoratorProvider(NotifyProvider delegate, int retries) {
            this.delegate = delegate; this.retries = retries;
        }
        @Override public void send(String to, String content) {
            for (int i = 0; i <= retries; i++) {
                try { delegate.send(to, content); return; }
                catch (Exception e) {
                    System.out.println("  [Retry] attempt " + (i + 1) + " failed: " + e.getMessage());
                }
            }
            throw new RuntimeException("all retries exhausted");
        }
        @Override public String channel() {
            if (delegate instanceof TargetAware) {
                return ((TargetAware) delegate).channel() + "+retry";
            }
            return "decorated";
        }
    }

    public static void main(String[] args) {
        NotifyProvider provider = new RetryDecoratorProvider(new SmsProvider(), 2);

        if (provider instanceof TargetAware) {
            System.out.println("🚦 当前渠道: " + ((TargetAware) provider).channel());
        }
        provider.send("13800138000", "您的茶饮已就绪");
    }
}
