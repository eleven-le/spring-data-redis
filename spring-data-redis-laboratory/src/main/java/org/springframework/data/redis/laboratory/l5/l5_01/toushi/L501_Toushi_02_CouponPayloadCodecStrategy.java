package org.springframework.data.redis.laboratory.l5.l5_01.toushi;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 偷师 2：优惠券发放消息体在 MQ / Redis / DB 扩展字段中使用不同编码——
 * 抽出 {@link CouponPayloadCodec} 策略，让上层"发放器"完全不感知编码差异。
 * <p>
 * 对应文档：L5-01 → §6.4 开闭原则 / §6.5 基础设施抽象。
 * <p>
 * 背景（真实 C 端常见）：
 * <ul>
 *   <li>MQ：用 JSON。下游消费者多，要求人类可读。</li>
 *   <li>Redis 发放记录：用紧凑短串。HOT key 不能浪费内存。</li>
 *   <li>DB 风控扩展字段（varchar(512)）：用 Base64(紧凑短串)。要进 SQL，必须可见字符。</li>
 * </ul>
 * 如果上层 CouponIssueService 里一段 if (target=="mq") {...} else if (target=="redis") {...}，
 * 每加一个目的地都要改这个类——这是经典的开闭原则反例。
 * <p>
 * 抽出 CouponPayloadCodec 接口后：
 * 新增"端"只要新增一个实现；新增"字段"只要在 CouponPayload 上扩。
 * 上层永远只调 {@code codec.encode(payload)} / {@code codec.decode(bytes)}。
 * <p>
 * RedisSerializer 在 Spring Data Redis 干的就是这件事——
 * RedisTemplate 不知道你 JSON 还是 JDK；它只知道 byte[]。
 */
public class L501_Toushi_02_CouponPayloadCodecStrategy {

    /**
     * 优惠券发放消息体——字段是 C 端真实业务字段而不是抽象 a/b/c：
     * <ul>
     *   <li>userId/couponId/activityId 三元组定位发放对象与来源</li>
     *   <li>expireAt 失效时间（毫秒）</li>
     *   <li>source 来源：邀请、活动、补偿、积分兑换……</li>
     *   <li>riskTag 风控标签，影响后续核销时的拦截策略</li>
     * </ul>
     */
    public static final class CouponPayload {
        private final String userId;
        private final String couponId;
        private final String activityId;
        private final long expireAt;
        private final String source;
        private final String riskTag;

        public CouponPayload(String userId, String couponId, String activityId,
                             long expireAt, String source, String riskTag) {
            this.userId = userId;
            this.couponId = couponId;
            this.activityId = activityId;
            this.expireAt = expireAt;
            this.source = source;
            this.riskTag = riskTag;
        }

        public String getUserId()     { return userId; }
        public String getCouponId()   { return couponId; }
        public String getActivityId() { return activityId; }
        public long getExpireAt()     { return expireAt; }
        public String getSource()     { return source; }
        public String getRiskTag()    { return riskTag; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CouponPayload)) return false;
            CouponPayload p = (CouponPayload) o;
            return expireAt == p.expireAt
                    && Objects.equals(userId, p.userId)
                    && Objects.equals(couponId, p.couponId)
                    && Objects.equals(activityId, p.activityId)
                    && Objects.equals(source, p.source)
                    && Objects.equals(riskTag, p.riskTag);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, couponId, activityId, expireAt, source, riskTag);
        }

        @Override
        public String toString() {
            return "CouponPayload{u=" + userId + ",c=" + couponId + ",a=" + activityId +
                    ",exp=" + expireAt + ",src=" + source + ",risk=" + riskTag + "}";
        }
    }

    /** 编解码策略接口——对标 RedisSerializer。 */
    public interface CouponPayloadCodec {
        byte[] encode(CouponPayload payload);
        CouponPayload decode(byte[] bytes);
        String channelHint();
    }

    /** MQ 实现：JSON，最易排查。 */
    public static class JsonCouponPayloadCodec implements CouponPayloadCodec {
        @Override public byte[] encode(CouponPayload p) {
            String json = "{" +
                    "\"userId\":\""     + p.userId     + "\"," +
                    "\"couponId\":\""   + p.couponId   + "\"," +
                    "\"activityId\":\"" + p.activityId + "\"," +
                    "\"expireAt\":"     + p.expireAt   + ","   +
                    "\"source\":\""     + p.source     + "\"," +
                    "\"riskTag\":\""    + p.riskTag    + "\""  +
                    "}";
            return json.getBytes(StandardCharsets.UTF_8);
        }

        @Override public CouponPayload decode(byte[] bytes) {
            Map<String, String> kv = parseFlatJson(new String(bytes, StandardCharsets.UTF_8));
            return new CouponPayload(
                    kv.get("userId"), kv.get("couponId"), kv.get("activityId"),
                    Long.parseLong(kv.get("expireAt")), kv.get("source"), kv.get("riskTag")
            );
        }
        @Override public String channelHint() { return "mq:json"; }
    }

    /**
     * Redis 实现：用 '|' 分割的紧凑短串。
     * 6 个字段拼成 "u-1001|c-618|a-2026|1717000000000|invite|low"——
     * 比 JSON 短一半左右，作为 HOT key 的 value 更省内存。
     */
    public static class CompactCouponPayloadCodec implements CouponPayloadCodec {
        private static final char SEP = '|';

        @Override public byte[] encode(CouponPayload p) {
            String compact = p.userId + SEP + p.couponId + SEP + p.activityId + SEP
                    + p.expireAt + SEP + p.source + SEP + p.riskTag;
            return compact.getBytes(StandardCharsets.UTF_8);
        }

        @Override public CouponPayload decode(byte[] bytes) {
            String s = new String(bytes, StandardCharsets.UTF_8);
            String[] parts = s.split("\\|", -1);
            if (parts.length != 6) {
                throw new IllegalStateException("bad compact payload: " + s);
            }
            return new CouponPayload(parts[0], parts[1], parts[2],
                    Long.parseLong(parts[3]), parts[4], parts[5]);
        }
        @Override public String channelHint() { return "redis:compact"; }
    }

    /** DB varchar 实现：Base64(紧凑短串)。可见字符可入库，又比 JSON 短。 */
    public static class Base64CompactCouponPayloadCodec implements CouponPayloadCodec {
        private final CompactCouponPayloadCodec delegate = new CompactCouponPayloadCodec();

        @Override public byte[] encode(CouponPayload p) {
            byte[] compact = delegate.encode(p);
            return Base64.getEncoder().encode(compact);
        }

        @Override public CouponPayload decode(byte[] bytes) {
            byte[] compact = Base64.getDecoder().decode(bytes);
            return delegate.decode(compact);
        }
        @Override public String channelHint() { return "db:base64-compact"; }
    }

    /**
     * 一个极简的发放服务：上层只依赖 CouponPayloadCodec 接口，不关心目的地。
     * 真实业务里这里就是 CouponIssueService + 三种 Adapter；策略保持不变。
     */
    public static class CouponDispatcher {
        private final CouponPayloadCodec codec;
        public CouponDispatcher(CouponPayloadCodec codec) { this.codec = codec; }

        public byte[] dispatch(CouponPayload payload) {
            byte[] bytes = codec.encode(payload);
            // 真实业务在这里：mq.send(bytes) / redis.set(key, bytes) / jdbc.insert(base64Str)
            return bytes;
        }

        public CouponPayload recover(byte[] bytes) {
            return codec.decode(bytes);
        }

        public String channelHint() { return codec.channelHint(); }
    }

    public static void main(String[] args) {
        CouponPayload payload = new CouponPayload(
                "u-1001", "c-618", "a-2026-618",
                1_717_000_000_000L, "invite", "low");

        for (CouponPayloadCodec codec : new CouponPayloadCodec[]{
                new JsonCouponPayloadCodec(),
                new CompactCouponPayloadCodec(),
                new Base64CompactCouponPayloadCodec()
        }) {
            CouponDispatcher dispatcher = new CouponDispatcher(codec);
            byte[] encoded = dispatcher.dispatch(payload);
            CouponPayload back = dispatcher.recover(encoded);
            System.out.printf("%-22s size=%3d  raw=%s  recoveredOk=%s%n",
                    codec.channelHint(),
                    encoded.length,
                    new String(encoded, StandardCharsets.UTF_8),
                    payload.equals(back));
        }

        System.out.println();
        System.out.println("→ 偷师结论：编码差异被锁在 codec 里，上层 CouponDispatcher 闭口不谈编码——");
        System.out.println("  对扩展开放（新增一个 Codec），对修改关闭（Dispatcher 一行不改）。");
        System.out.println("  这正是 Spring Data Redis 让 RedisTemplate 配 4 把 Serializer 的同一套思路。");
    }

    /** 极简 JSON 解析，只为零依赖，不处理嵌套/转义。生产请用 Jackson。 */
    private static Map<String, String> parseFlatJson(String json) {
        Map<String, String> kv = new LinkedHashMap<>();
        String body = json.trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}"))   body = body.substring(0, body.length() - 1);
        for (String part : body.split(",")) {
            int colon = part.indexOf(':');
            String key = strip(part.substring(0, colon));
            String val = strip(part.substring(colon + 1));
            kv.put(key, val);
        }
        return kv;
    }

    private static String strip(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }
}
