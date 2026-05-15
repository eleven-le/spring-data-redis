package org.springframework.data.redis.laboratory.l5.l5_02.production;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_02.L502Keys;
import org.springframework.data.redis.laboratory.l5.l5_02.compare.L502_02_StringVsJsonVsJdkCompareScenario.UserProfile;
import org.springframework.data.redis.laboratory.l5.l5_02.config.L502_01_SerializerFamilyLabConfig;
import org.springframework.data.redis.laboratory.l5.l5_02.jackson.L502_03_JacksonSerializerChoiceScenario.ProductDetailCacheDTO;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 真实场景：一个 C 端项目里，不同缓存使用不同 RedisTemplate。
 * <p>
 * 对应文档：L5-02 → §3 / §6.4 工程纪律。
 * <p>
 * 这一节模拟"一个真实 C 端业务"按职责拆分 4 套缓存：
 * <ol>
 *   <li>{@link TokenCache} —— 登录态 Token，key/value 全 String。</li>
 *   <li>{@link UserProfileCache} —— 用户画像，单服务内部读写，走 Generic JSON。</li>
 *   <li>{@link ProductDetailCache} —— 商品详情快照，搜索/推荐/商详服务都要读,走 Typed JSON DTO。</li>
 *   <li>{@link RiskTagCache} —— 风控标签轻量缓存,调用方握 Protobuf 字节,走 byte[] 透传。</li>
 * </ol>
 * <p>
 * 演示要点：
 * <ul>
 *   <li>每种缓存的 RedisTemplate 都是<b>显式选择</b>的——不是"看心情",而是"看业务约束"；</li>
 *   <li>"一个 RedisTemplate 打天下" = 把简单值 JSON 化 / 把跨服务对象绑死 Java 类路径 /
 *       把字段稳定 DTO 暴露给 Generic 的 @class 风险——这些都是真实事故的母题；</li>
 *   <li>Code Review 红线：业务代码里不允许出现 {@code new RedisTemplate<>()},
 *       Template 一律走 Bean，按业务前缀命名。</li>
 * </ul>
 * <p>
 * 运行后 redis-cli 观察四种 value 形态完全不同：
 * <pre>
 *   GET 'l5:02:token:user:u-1001'           → ASCII 字符串
 *   GET 'l5:02:profile:user:u-1001'         → JSON + @class
 *   GET 'l5:02:product:detail:SPU-1000'     → 干净 JSON
 *   GET 'l5:02:risk:tag:u-1001'             → 任意字节（这里用伪 Protobuf 演示）
 * </pre>
 */
public class L502_04_MultiTemplateProductionChoiceScenario {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L502_01_SerializerFamilyLabConfig.class)) {

            TokenCache tokenCache = new TokenCache(ctx.getBean(StringRedisTemplate.class));
            UserProfileCache profileCache = new UserProfileCache(
                    (RedisTemplate<String, Object>) ctx.getBean("genericJsonRedisTemplate", RedisTemplate.class));
            ProductDetailCache productCache = new ProductDetailCache(
                    (RedisTemplate<String, ProductDetailCacheDTO>) ctx.getBean("typedJacksonRedisTemplate", RedisTemplate.class));
            RiskTagCache riskCache = new RiskTagCache(
                    (RedisTemplate<String, byte[]>) ctx.getBean("byteArrayRedisTemplate", RedisTemplate.class));

            // ① Token 缓存（登录链路最热）
            tokenCache.cache("u-1001", "tk-2026-AB12CD34EF56", 30);
            System.out.println("[token]   " + tokenCache.get("u-1001"));

            // ② 用户画像（单服务内部）
            profileCache.cache(UserProfile.sample("u-1001"));
            System.out.println("[profile] " + profileCache.get("u-1001"));

            // ③ 商品详情（跨服务共享 → 稳定 DTO）
            productCache.cache(ProductDetailCacheDTO.sample("SPU-1000"));
            System.out.println("[product] " + productCache.get("SPU-1000"));

            // ④ 风控标签（伪 Protobuf 字节透传演示）
            byte[] riskFeature = encodePseudoProtobuf("u-1001", 0.92f, "high_value");
            riskCache.cache("u-1001", riskFeature);
            byte[] back = riskCache.get("u-1001");
            System.out.println("[risk]    size=" + back.length + " bytes");

            System.out.println();
            System.out.println("→ 4 种缓存 4 套序列化策略,互不干扰。");
            System.out.println("  这就是\"按业务拆 Template\"的工程红利——线上排障时,看 key 前缀就知道走的是哪种字节。");
        }
    }

    /** 仿造一个 Protobuf-like 二进制：magic + version + userIdLen + userId + scoreBits + tagLen + tag。 */
    private static byte[] encodePseudoProtobuf(String userId, float score, String tag) {
        byte[] userBytes = userId.getBytes(StandardCharsets.UTF_8);
        byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
        int total = 2 + 1 + userBytes.length + 4 + 1 + tagBytes.length;
        byte[] buf = new byte[total];
        int p = 0;
        buf[p++] = 0x52;     // magic
        buf[p++] = 0x01;     // version
        buf[p++] = (byte) userBytes.length;
        System.arraycopy(userBytes, 0, buf, p, userBytes.length);
        p += userBytes.length;
        int bits = Float.floatToIntBits(score);
        buf[p++] = (byte) (bits >> 24);
        buf[p++] = (byte) (bits >> 16);
        buf[p++] = (byte) (bits >> 8);
        buf[p++] = (byte) bits;
        buf[p++] = (byte) tagBytes.length;
        System.arraycopy(tagBytes, 0, buf, p, tagBytes.length);
        return buf;
    }

    // ─────────────────────── 缓存门面（按业务拆,这是工程纪律的体现） ───────────────────────

    /** 登录态 Token 缓存——纯 String。 */
    public static class TokenCache {
        private final StringRedisTemplate template;
        public TokenCache(StringRedisTemplate template) { this.template = template; }

        public void cache(String userId, String token, long minutes) {
            template.opsForValue().set(L502Keys.TOKEN_USER + userId, token, minutes, TimeUnit.MINUTES);
        }
        public String get(String userId) {
            return template.opsForValue().get(L502Keys.TOKEN_USER + userId);
        }
    }

    /** 用户画像缓存——单服务内部,Generic JSON。 */
    public static class UserProfileCache {
        private final RedisTemplate<String, Object> template;
        public UserProfileCache(RedisTemplate<String, Object> template) { this.template = template; }

        public void cache(UserProfile profile) {
            template.opsForValue().set(L502Keys.PROFILE_USER + profile.getUserId(),
                    profile, 7, TimeUnit.DAYS);
        }
        public UserProfile get(String userId) {
            return (UserProfile) template.opsForValue().get(L502Keys.PROFILE_USER + userId);
        }
    }

    /** 商品详情缓存——跨服务共享,Typed JSON DTO,不绑死 Java 类路径。 */
    public static class ProductDetailCache {
        private final RedisTemplate<String, ProductDetailCacheDTO> template;
        public ProductDetailCache(RedisTemplate<String, ProductDetailCacheDTO> template) { this.template = template; }

        public void cache(ProductDetailCacheDTO dto) {
            template.opsForValue().set(L502Keys.PRODUCT_DETAIL + dto.getSpuId(),
                    dto, 1, TimeUnit.HOURS);
        }
        public ProductDetailCacheDTO get(String spuId) {
            return template.opsForValue().get(L502Keys.PRODUCT_DETAIL + spuId);
        }
    }

    /** 风控标签轻量缓存——调用方握 byte[],透传。 */
    public static class RiskTagCache {
        private final RedisTemplate<String, byte[]> template;
        public RiskTagCache(RedisTemplate<String, byte[]> template) { this.template = template; }

        public void cache(String userId, byte[] feature) {
            template.opsForValue().set(L502Keys.RISK_TAG + userId, feature, 5, TimeUnit.MINUTES);
        }
        public byte[] get(String userId) {
            return template.opsForValue().get(L502Keys.RISK_TAG + userId);
        }
    }
}
