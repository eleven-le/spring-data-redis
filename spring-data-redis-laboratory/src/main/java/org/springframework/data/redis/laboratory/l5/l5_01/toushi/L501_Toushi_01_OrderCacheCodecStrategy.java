package org.springframework.data.redis.laboratory.l5.l5_01.toushi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * 偷师 1：把 RedisSerializer 的策略骨架搬到"订单缓存编码"。
 * <p>
 * 对应文档：L5-01 → §6.1 策略模式 / §6.3 组合优于继承 / §6.4 开闭原则。
 * <p>
 * 真实背景：
 * 订单缓存在 C 端不同链路里要求不一样——
 * <ul>
 *   <li>商详/购物车 这种走 BFF 高频读：JSON 即可，方便排障。</li>
 *   <li>跨机房 / 跨城同步：JSON 太胖，先 GZIP 压缩。</li>
 *   <li>Feed 召回 / 风控离线特征：要拼长二进制串，自定义紧凑布局更省 RTT。</li>
 * </ul>
 * 如果在 OrderCacheTemplate 里 if/else 三段写，每加一种编码都要改一遍 Template；
 * 抽出 {@link OrderCacheCodec} 接口后，新增编码只需要写一个实现，
 * Template 一行代码都不用改——这就是 RedisSerializer 在 RedisTemplate 上演示的同一个开闭原则。
 * <p>
 * 这里没有连真实 Redis，目的是把"策略接口 + 组合 + 模板方法"的骨头讲清楚，
 * 让你下次设计支付渠道、消息体编码、导出格式时第一反应是抽接口。
 */
public class L501_Toushi_01_OrderCacheCodecStrategy {

    /**
     * 订单缓存编解码策略接口——对标 {@code org.springframework.data.redis.serializer.RedisSerializer}。
     * <p>
     * 最小契约：byte[] ↔ Order。具体怎么编码、压不压缩、定不定二进制布局，是实现内部的事。
     */
    public interface OrderCacheCodec {
        byte[] encode(Order order);

        Order decode(byte[] bytes);

        /**
         * 用一个简单标识方便排障——对标 RedisSerializer 没有但生产里我们一般加上的"version/contentType"。
         */
        String contentType();
    }

    /**
     * 普通 JSON 编码——读写最直观，便于线上排查。
     */
    public static class JsonOrderCacheCodec implements OrderCacheCodec {
        @Override
        public byte[] encode(Order o) {
            // 用 toJson 而不是依赖 Jackson，是为了让示例零依赖
            return o.toJson().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Order decode(byte[] bytes) {
            return Order.fromJson(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public String contentType() {
            return "application/json";
        }
    }

    /**
     * GZIP 压缩 JSON——跨机房同步、长尾大对象用，CPU 换网络。
     */
    public static class CompressedJsonOrderCacheCodec implements OrderCacheCodec {
        private final JsonOrderCacheCodec delegate = new JsonOrderCacheCodec();

        @Override
        public byte[] encode(Order o) {
            byte[] raw = delegate.encode(o);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(raw);
                gzip.finish();
                return baos.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("gzip encode failed", e);
            }
        }

        @Override
        public Order decode(byte[] bytes) {
            try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
                 java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(bais);
                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = gzip.read(buf)) != -1) baos.write(buf, 0, n);
                return delegate.decode(baos.toByteArray());
            } catch (Exception e) {
                throw new IllegalStateException("gzip decode failed", e);
            }
        }

        @Override
        public String contentType() {
            return "application/json+gzip";
        }
    }

    /**
     * 自定义紧凑二进制编码——
     * 用 magic + version + 字段长度前缀的方式手撸，模拟召回 / 特征链路。
     * 真生产里就把这一层换成 Protobuf/FlatBuffers，但策略接口一行不用动。
     */
    public static class BinaryOrderCacheCodec implements OrderCacheCodec {
        private static final byte MAGIC = 0x5A;
        private static final byte VERSION = 0x01;

        @Override
        public byte[] encode(Order o) {
            byte[] idBytes = o.id.getBytes(StandardCharsets.UTF_8);
            byte[] userBytes = o.userId.getBytes(StandardCharsets.UTF_8);
            long priceCents = o.priceCents;
            int total = 2 + 2 + idBytes.length + 2 + userBytes.length + 8;
            byte[] buf = new byte[total];
            int p = 0;
            buf[p++] = MAGIC;
            buf[p++] = VERSION;
            p = writeLenString(buf, p, idBytes);
            p = writeLenString(buf, p, userBytes);
            for (int i = 7; i >= 0; i--) buf[p++] = (byte) ((priceCents >> (i * 8)) & 0xff);
            return buf;
        }

        @Override
        public Order decode(byte[] bytes) {
            if (bytes[0] != MAGIC || bytes[1] != VERSION) {
                throw new IllegalStateException("bad magic/version");
            }
            int p = 2;
            int idLen = ((bytes[p++] & 0xff) << 8) | (bytes[p++] & 0xff);
            String id = new String(bytes, p, idLen, StandardCharsets.UTF_8);
            p += idLen;
            int userLen = ((bytes[p++] & 0xff) << 8) | (bytes[p++] & 0xff);
            String user = new String(bytes, p, userLen, StandardCharsets.UTF_8);
            p += userLen;
            long cents = 0;
            for (int i = 0; i < 8; i++) cents = (cents << 8) | (bytes[p++] & 0xff);
            return new Order(id, user, cents);
        }

        @Override
        public String contentType() {
            return "application/x-order-binary;v=1";
        }

        private static int writeLenString(byte[] buf, int p, byte[] data) {
            buf[p++] = (byte) ((data.length >> 8) & 0xff);
            buf[p++] = (byte) (data.length & 0xff);
            System.arraycopy(data, 0, buf, p, data.length);
            return p + data.length;
        }
    }

    /**
     * 对标 RedisTemplate：固定 put/get 流程，把"怎么编码"完全甩给 codec。
     * 这就是组合优于继承——OrderCacheTemplate 不通过继承"成为某个编码器"，而是组合一个进来。
     */
    public static class OrderCacheTemplate {
        private final OrderCacheCodec codec;
        // 这里换成 RedisConnection 就是真的 RedisTemplate；这里用内存 Map 把存储抽象掉，重点看策略
        private final java.util.Map<String, byte[]> store = new java.util.concurrent.ConcurrentHashMap<>();

        public OrderCacheTemplate(OrderCacheCodec codec) {
            this.codec = codec;
        }

        public void put(String key, Order order) {
            store.put(key, codec.encode(order));
        }

        public Order get(String key) {
            byte[] bytes = store.get(key);
            return bytes == null ? null : codec.decode(bytes);
        }

        public int sizeBytes(String key) {
            byte[] bytes = store.get(key);
            return bytes == null ? 0 : bytes.length;
        }
    }

    /**
     * 极简 Order POJO，自带 toJson/fromJson 让示例零依赖。
     */
    public static class Order {
        public final String id;
        public final String userId;
        public final long priceCents;

        public Order(String id, String userId, long priceCents) {
            this.id = id;
            this.userId = userId;
            this.priceCents = priceCents;
        }

        public String toJson() {
            return "{\"id\":\"" + id + "\",\"userId\":\"" + userId + "\",\"priceCents\":" + priceCents + "}";
        }

        public static Order fromJson(String json) {
            String id = pick(json, "\"id\":\"", "\"");
            String userId = pick(json, "\"userId\":\"", "\"");
            String price = pick(json, "\"priceCents\":", "}");
            return new Order(id, userId, Long.parseLong(price.trim()));
        }

        private static String pick(String s, String start, String end) {
            int a = s.indexOf(start) + start.length();
            int b = s.indexOf(end, a);
            return s.substring(a, b);
        }

        @Override
        public String toString() {
            return "Order{" + id + "," + userId + "," + priceCents + "分}";
        }
    }

    /**
     * 偷师演示主流程：同一个 Template 换 3 把策略，体积/可读性差异立现。
     */
    public static void main(String[] args) {
        Order order = new Order("O-10086", "u-1001", 3990L);

        for (OrderCacheCodec codec : new OrderCacheCodec[]{
                new JsonOrderCacheCodec(),
                new CompressedJsonOrderCacheCodec(),
                new BinaryOrderCacheCodec()
        }) {
            OrderCacheTemplate template = new OrderCacheTemplate(codec);
            template.put("order:" + order.id, order);
            int size = template.sizeBytes("order:" + order.id);
            Order back = template.get("order:" + order.id);
            System.out.printf("%-30s size=%3d bytes  decoded=%s%n",
                    codec.contentType(), size, back);
        }

        System.out.println();
        System.out.println("→ 偷师结论：同一个 OrderCacheTemplate，换 codec 就换字节布局；");
        System.out.println("  这就是 RedisTemplate 不关心你写 JSON 还是 Protobuf 的底层原因——它面向 RedisSerializer 接口编程，不耦合实现。");
    }
}
