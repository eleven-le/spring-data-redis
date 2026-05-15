package org.springframework.data.redis.laboratory.l5.l5_02.jackson;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_02.L502Keys;
import org.springframework.data.redis.laboratory.l5.l5_02.config.L502_01_SerializerFamilyLabConfig;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 真实场景：首页营销卡片（多态） vs 商品详情快照（固定 DTO）。
 * <p>
 * 对应文档：L5-02 → §4 JSON 家族重点对比。
 * <p>
 * 这一节同时演示两种 Jackson 序列化器：
 * <ul>
 *   <li>{@link org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer}
 *       —— 写入 {@code @class} 元信息，反序列化能完整还原 CouponCard / BannerCard / ProductCard。</li>
 *   <li>{@link org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer}
 *       —— 构造时绑死 {@link ProductDetailCacheDTO}.class，JSON 干净，跨服务/跨语言友好。</li>
 * </ul>
 * 关键观察：
 * <ol>
 *   <li>Generic 写出来的 JSON 第一个字段就是 {@code "@class":"..."}——这是它能还原任意类型的能力来源,也是跨服务风险来源。</li>
 *   <li>Typed 写出来的 JSON 没有任何 Java 类名痕迹——Go/Node 直接消费没毛病。</li>
 *   <li>多态 List 用 Typed 序列化器会丢子类字段（reify 成父类型）——这就是 §4.2 讲的"Typed 不适合多态"的根因。</li>
 * </ol>
 * 运行后 redis-cli 观察：
 * <pre>
 *   GET 'l5:02:home:cards:home:2026'
 *   --> ["java.util.ArrayList",[{"@class":"...CouponCard",...},{"@class":"...BannerCard",...}]]
 *
 *   GET 'l5:02:product:detail:SPU-1000'
 *   --> {"spuId":"SPU-1000","title":"...","price":12.50,...}  // 干净,无 @class
 * </pre>
 */
public class L502_03_JacksonSerializerChoiceScenario {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(L502_01_SerializerFamilyLabConfig.class)) {

            @SuppressWarnings("unchecked")
            RedisTemplate<String, Object> generic =
                    ctx.getBean("genericJsonRedisTemplate", RedisTemplate.class);
            @SuppressWarnings("unchecked")
            RedisTemplate<String, ProductDetailCacheDTO> typed =
                    ctx.getBean("typedJacksonRedisTemplate", RedisTemplate.class);

            // ① 多态卡片列表 —— Generic 主战场
            String homeKey = L502Keys.HOME_CARDS + "home:2026";
            List<ActivityCard> cards = sampleCards();
            generic.opsForValue().set(homeKey, cards);

            Object loadedCards = generic.opsForValue().get(homeKey);
            System.out.println("══════ Generic 还原多态列表 ══════");
            ((List<?>) loadedCards).forEach(c -> {
                ActivityCard card = (ActivityCard) c;
                System.out.println("  [" + card.cardType() + "] class=" + card.getClass().getSimpleName()
                        + " toString=" + card);
            });

            // ② 固定 DTO —— Typed 主战场
            String productKey = L502Keys.PRODUCT_DETAIL + "SPU-1000";
            ProductDetailCacheDTO product = ProductDetailCacheDTO.sample("SPU-1000");
            typed.opsForValue().set(productKey, product);

            ProductDetailCacheDTO loadedProduct = typed.opsForValue().get(productKey);
            System.out.println();
            System.out.println("══════ Typed 还原固定 DTO ══════");
            System.out.println("  " + loadedProduct);

            System.out.println();
            System.out.println("→ 下一步：");
            System.out.println("  redis-cli GET 'l5:02:home:cards:home:2026'    可见 @class（Generic）");
            System.out.println("  redis-cli GET 'l5:02:product:detail:SPU-1000'  无 @class（Typed）");
            System.out.println("  这两条 JSON 的差异,就是单服务内部 vs 跨服务共享的真实选择。");

            // 清理
            generic.delete(homeKey);
            typed.delete(productKey);
        }
    }

    public static List<ActivityCard> sampleCards() {
        return Arrays.asList(
                new CouponCard("c-1001", "立减 5 元", new BigDecimal("5.00")),
                new BannerCard("b-2001", "夏日清凉季", "https://cdn.example.com/banner.png"),
                new ProductCard("p-3001", "经典美式", "SPU-1000", new BigDecimal("12.00"))
        );
    }

    // ─────────────────────── 多态卡片模型 ───────────────────────

    /** 活动卡片父接口——演示 Generic 对 List&lt;父接口&gt; 的还原能力。 */
    public interface ActivityCard {
        String cardType();
    }

    public static class CouponCard implements ActivityCard {
        private String couponId;
        private String title;
        private BigDecimal discount;

        public CouponCard() {}
        public CouponCard(String couponId, String title, BigDecimal discount) {
            this.couponId = couponId; this.title = title; this.discount = discount;
        }
        @Override public String cardType() { return "coupon"; }

        public String getCouponId()       { return couponId; }
        public String getTitle()          { return title; }
        public BigDecimal getDiscount()   { return discount; }
        public void setCouponId(String s)     { this.couponId = s; }
        public void setTitle(String s)        { this.title = s; }
        public void setDiscount(BigDecimal d) { this.discount = d; }

        @Override public String toString() {
            return "Coupon{" + couponId + "," + title + ",-" + discount + "}";
        }
    }

    public static class BannerCard implements ActivityCard {
        private String bannerId;
        private String title;
        private String imageUrl;

        public BannerCard() {}
        public BannerCard(String bannerId, String title, String imageUrl) {
            this.bannerId = bannerId; this.title = title; this.imageUrl = imageUrl;
        }
        @Override public String cardType() { return "banner"; }

        public String getBannerId()  { return bannerId; }
        public String getTitle()     { return title; }
        public String getImageUrl()  { return imageUrl; }
        public void setBannerId(String s) { this.bannerId = s; }
        public void setTitle(String s)    { this.title = s; }
        public void setImageUrl(String s) { this.imageUrl = s; }

        @Override public String toString() {
            return "Banner{" + bannerId + "," + title + "}";
        }
    }

    public static class ProductCard implements ActivityCard {
        private String productId;
        private String title;
        private String spuId;
        private BigDecimal price;

        public ProductCard() {}
        public ProductCard(String productId, String title, String spuId, BigDecimal price) {
            this.productId = productId; this.title = title;
            this.spuId = spuId; this.price = price;
        }
        @Override public String cardType() { return "product"; }

        public String getProductId() { return productId; }
        public String getTitle()     { return title; }
        public String getSpuId()     { return spuId; }
        public BigDecimal getPrice() { return price; }
        public void setProductId(String s) { this.productId = s; }
        public void setTitle(String s)     { this.title = s; }
        public void setSpuId(String s)     { this.spuId = s; }
        public void setPrice(BigDecimal p) { this.price = p; }

        @Override public String toString() {
            return "Product{" + productId + "," + title + ",¥" + price + "}";
        }
    }

    // ─────────────────────── 固定 DTO 模型 ───────────────────────

    /**
     * 商品详情缓存 DTO——给跨服务共享缓存用。
     * <p>
     * 注意：这是个**缓存协议 DTO**，不是领域 Entity。
     * 一旦上线就当线上协议管，字段只加不删不改名。
     */
    public static class ProductDetailCacheDTO {
        private String spuId;
        private String title;
        private String description;
        private BigDecimal price;
        private int stock;
        private long updatedAtMillis;

        public ProductDetailCacheDTO() {}
        public ProductDetailCacheDTO(String spuId, String title, String description,
                                     BigDecimal price, int stock, long updatedAtMillis) {
            this.spuId = spuId; this.title = title; this.description = description;
            this.price = price; this.stock = stock; this.updatedAtMillis = updatedAtMillis;
        }

        public static ProductDetailCacheDTO sample(String spuId) {
            return new ProductDetailCacheDTO(spuId, "经典美式", "100% 阿拉比卡豆,中度烘焙",
                    new BigDecimal("12.00"), 9999, System.currentTimeMillis());
        }

        public String getSpuId()           { return spuId; }
        public String getTitle()           { return title; }
        public String getDescription()     { return description; }
        public BigDecimal getPrice()       { return price; }
        public int getStock()              { return stock; }
        public long getUpdatedAtMillis()   { return updatedAtMillis; }
        public void setSpuId(String s)         { this.spuId = s; }
        public void setTitle(String s)         { this.title = s; }
        public void setDescription(String s)   { this.description = s; }
        public void setPrice(BigDecimal p)     { this.price = p; }
        public void setStock(int s)            { this.stock = s; }
        public void setUpdatedAtMillis(long t) { this.updatedAtMillis = t; }

        @Override public String toString() {
            return "ProductDetailCacheDTO{" + spuId + "," + title + ",¥" + price + ",stock=" + stock + "}";
        }
    }
}
