package org.springframework.data.redis.laboratory.l5.l5_02.toushi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 偷师 1：把 RedisSerializer 家族的策略骨架,搬到"消息体编码家族"。
 * <p>
 * 对应文档：L5-02 → §1 / §9.1 静态工厂家族 / §9.6 ByteArrayRedisSerializer 透传家族。
 * <p>
 * 真实背景：
 * C 端系统里一条消息体（MQ Payload / 内部 RPC Body / 跨服务事件）通常要适应不同链路：
 * <ul>
 *   <li>排查友好链路：JSON,redis-cli/控制台一眼能读；</li>
 *   <li>大消息体链路：JSON + GZIP,CPU 换网络；</li>
 *   <li>高吞吐内部链路：自定义紧凑二进制（或 Protobuf）,榨干字节。</li>
 * </ul>
 * 如果在 PayloadTemplate 里 if/else 三段写,每加一种编码都要改 Template；
 * 抽出 {@link MessagePayloadCodec} 接口后,新增编码只需要写一个实现,
 * Template 一行代码都不用改——
 * <p>
 * 这正是 RedisSerializer 在 RedisTemplate 上演示的同一个开闭原则 + 组合优于继承。
 * <p>
 * 不需要连接 Redis,纯内存对比体积。
 */
public class L502_Toushi_01_MessagePayloadCodecFamily {

    // ─────────────────────── 策略接口（对标 RedisSerializer） ───────────────────────

    /**
     * 消息体编解码策略接口。
     * <p>
     * 最小契约：byte[] ↔ MessagePayload。
     * 加了一个 {@link #contentType()} 用于线上排查时识别字节布局（生产里强烈建议加）。
     */
    public interface MessagePayloadCodec {
        byte[] encode(MessagePayload payload);
        MessagePayload decode(byte[] bytes);
        String contentType();
    }

    // ─────────────────────── 三种实现（对标 String / GenericJson / 自定义 byte[]） ───────────────────────

    /** JSON 编码——排障友好,默认实现。 */
    public static class JsonMessagePayloadCodec implements MessagePayloadCodec {
        @Override public byte[] encode(MessagePayload p) {
            return p.toJson().getBytes(StandardCharsets.UTF_8);
        }
        @Override public MessagePayload decode(byte[] bytes) {
            return MessagePayload.fromJson(new String(bytes, StandardCharsets.UTF_8));
        }
        @Override public String contentType() { return "application/json"; }
    }

    /** GZIP 压缩 JSON——大消息体用,CPU 换网络。 */
    public static class CompressedJsonMessagePayloadCodec implements MessagePayloadCodec {
        private final JsonMessagePayloadCodec delegate = new JsonMessagePayloadCodec();
        @Override public byte[] encode(MessagePayload p) {
            byte[] raw = delegate.encode(p);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(raw);
                gzip.finish();
                return baos.toByteArray();
            } catch (Exception e) { throw new IllegalStateException("gzip encode failed", e); }
        }
        @Override public MessagePayload decode(byte[] bytes) {
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                 GZIPInputStream gzip = new GZIPInputStream(bais);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = gzip.read(buf)) != -1) baos.write(buf, 0, n);
                return delegate.decode(baos.toByteArray());
            } catch (Exception e) { throw new IllegalStateException("gzip decode failed", e); }
        }
        @Override public String contentType() { return "application/json+gzip"; }
    }

    /**
     * 自定义紧凑二进制编码——
     * magic + version + 字段长度前缀,模拟召回/特征链路的"榨字节"需求。
     * 真生产里把这一层换成 Protobuf 即可,策略接口一行不动。
     */
    public static class BinaryMessagePayloadCodec implements MessagePayloadCodec {
        private static final byte MAGIC = 0x4D;
        private static final byte VERSION = 0x01;

        @Override public byte[] encode(MessagePayload p) {
            byte[] topic = p.topic.getBytes(StandardCharsets.UTF_8);
            byte[] body  = p.body.getBytes(StandardCharsets.UTF_8);
            int total = 2 + 2 + topic.length + 4 + body.length + 8;
            ByteBuffer buf = ByteBuffer.allocate(total);
            buf.put(MAGIC).put(VERSION);
            buf.putShort((short) topic.length).put(topic);
            buf.putInt(body.length).put(body);
            buf.putLong(p.timestamp);
            return buf.array();
        }
        @Override public MessagePayload decode(byte[] bytes) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            if (buf.get() != MAGIC || buf.get() != VERSION) {
                throw new IllegalStateException("bad magic/version");
            }
            int tlen = buf.getShort() & 0xffff;
            byte[] topic = new byte[tlen]; buf.get(topic);
            int blen = buf.getInt();
            byte[] body = new byte[blen]; buf.get(body);
            long ts = buf.getLong();
            return new MessagePayload(new String(topic, StandardCharsets.UTF_8),
                    new String(body, StandardCharsets.UTF_8), ts);
        }
        @Override public String contentType() { return "application/x-msg-binary;v=1"; }
    }

    // ─────────────────────── 模板（对标 RedisTemplate，组合优于继承） ───────────────────────

    /**
     * 消息体模板：固定 put/get 流程,把"怎么编码"完全甩给 codec。
     * 这就是组合优于继承——PayloadTemplate 不通过继承"成为某个编码器",而是组合一个进来。
     */
    public static class MessagePayloadTemplate {
        private final MessagePayloadCodec codec;
        // 用内存 Map 抽掉存储,聚焦策略本身
        private final java.util.Map<String, byte[]> store = new java.util.concurrent.ConcurrentHashMap<>();

        public MessagePayloadTemplate(MessagePayloadCodec codec) { this.codec = codec; }

        public void put(String key, MessagePayload p) { store.put(key, codec.encode(p)); }
        public MessagePayload get(String key) {
            byte[] bytes = store.get(key);
            return bytes == null ? null : codec.decode(bytes);
        }
        public int sizeBytes(String key) {
            byte[] bytes = store.get(key);
            return bytes == null ? 0 : bytes.length;
        }
        public String contentType() { return codec.contentType(); }
    }

    /** 极简消息体 POJO,自带 toJson/fromJson 让示例零依赖。 */
    public static class MessagePayload {
        public final String topic;
        public final String body;
        public final long timestamp;

        public MessagePayload(String topic, String body, long timestamp) {
            this.topic = topic; this.body = body; this.timestamp = timestamp;
        }
        public String toJson() {
            return "{\"topic\":\"" + topic + "\",\"body\":\"" + escape(body) + "\",\"timestamp\":" + timestamp + "}";
        }
        public static MessagePayload fromJson(String json) {
            String topic = pick(json, "\"topic\":\"", "\"");
            String body  = unescape(pick(json, "\"body\":\"", "\","));
            String ts    = pick(json, "\"timestamp\":", "}");
            return new MessagePayload(topic, body, Long.parseLong(ts.trim()));
        }
        private static String escape(String s)   { return s.replace("\"", "\\\""); }
        private static String unescape(String s) { return s.replace("\\\"", "\""); }
        private static String pick(String s, String start, String end) {
            int a = s.indexOf(start) + start.length();
            int b = s.indexOf(end, a);
            return s.substring(a, b);
        }
        @Override public String toString() {
            return "MessagePayload{" + topic + "," + body.length() + "B,@" + timestamp + "}";
        }
    }

    /**
     * 偷师演示主流程：同一个 Template 换 3 把策略,体积/可读性差异立现。
     * 这就是 RedisTemplate 不关心你写 JSON 还是 Protobuf 的底层原因——
     * 它面向 RedisSerializer 接口编程,不耦合实现。
     */
    public static void main(String[] args) {
        // 仿造一条"中等长度"的消息：模拟订单变更事件
        String fakeOrderJson = "{\"orderId\":\"O-10086\",\"userId\":\"u-1001\",\"amount\":12850,\"status\":\"PAID\"," +
                "\"items\":[{\"skuId\":\"100086\",\"qty\":2},{\"skuId\":\"200001\",\"qty\":1}]}";
        MessagePayload payload = new MessagePayload("order.changed", fakeOrderJson, System.currentTimeMillis());

        System.out.printf("%-32s %s%n", "codec", "size (bytes)");
        System.out.println("─".repeat(54));
        for (MessagePayloadCodec codec : new MessagePayloadCodec[]{
                new JsonMessagePayloadCodec(),
                new CompressedJsonMessagePayloadCodec(),
                new BinaryMessagePayloadCodec()
        }) {
            MessagePayloadTemplate template = new MessagePayloadTemplate(codec);
            template.put("msg:1", payload);
            int size = template.sizeBytes("msg:1");
            MessagePayload back = template.get("msg:1");
            System.out.printf("%-32s %5d   decoded.topic=%s%n",
                    codec.contentType(), size, back.topic);
        }

        System.out.println();
        System.out.println("→ 偷师结论：");
        System.out.println("  ① 同一个 PayloadTemplate 换 codec 就换字节布局,业务调用方一行不改;");
        System.out.println("  ② RedisSerializer 就是缩小版的 MessagePayloadCodec——两个方法的最小契约换最大可换;");
        System.out.println("  ③ 业务里遇到\"消息编码、导出格式、支付渠道、风控规则\"等多实现场景,");
        System.out.println("     第一反应应该是抽接口,而不是 if/else。");
    }
}
