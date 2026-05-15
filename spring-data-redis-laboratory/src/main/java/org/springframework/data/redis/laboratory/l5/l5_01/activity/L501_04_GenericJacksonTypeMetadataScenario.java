package org.springframework.data.redis.laboratory.l5.l5_01.activity;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l5.l5_01.L501Keys;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 真实场景：活动中心首页缓存 List&lt;ActivityCard&gt;，但卡片有多种子类（优惠券 / Banner / 商品）。
 * <p>
 * 对应文档：L5-01 → §3.3 GenericJackson2JsonRedisSerializer / §5 第 3 坑 / §6.1 策略模式。
 * <p>
 * 这是 GenericJackson2JsonRedisSerializer 与 Jackson2JsonRedisSerializer 的分水岭实验：
 * <ul>
 *   <li>GenericJackson2JsonRedisSerializer 会在 JSON 顶层写入 {@code "@class"}，
 *       反序列化时按 @class 还原成具体子类——下面的 List 读回来还是 CouponCard / BannerCard / ProductCard。</li>
 *   <li>Jackson2JsonRedisSerializer&lt;ActivityCard&gt; 没有 @class，
 *       反序列化只看构造时传入的目标 Class，多态会全部退化成父类，字段丢失。</li>
 * </ul>
 * 但 @class 不是免费的午餐——它是一把双刃剑：
 * <ol>
 *   <li>类路径必须稳定。一旦下游服务把 {@code com.xxx.ActivityCard} 重命名为 {@code com.yyy.ActivityCard}，
 *       缓存里的 @class 还指着旧名，反序列化必炸。</li>
 *   <li>跨语言不友好。Go/Node 看到 @class 完全摸不着头脑，得做转译。</li>
 *   <li>反序列化安全。Jackson 历史上爆过 default typing + 类路径上有 gadget = RCE 的漏洞，
 *       生产用 GenericJackson2JsonRedisSerializer 时务必升到修复版本，或自己配 PolymorphicTypeValidator。</li>
 * </ol>
 * 运行前：本地 Redis 可达。
 * 运行后观察：
 * <pre>
 *   redis-cli GET l5:01:activity:cards:home:2026
 *   --> ["java.util.ArrayList",[
 *         {"@class":"...CouponCard", ...},
 *         {"@class":"...BannerCard", ...},
 *         {"@class":"...ProductCard", ...}
 *       ]]
 * </pre>
 * 这一行 RAW JSON 就是"为什么类路径必须稳"的证据。
 */
public class L501_04_GenericJacksonTypeMetadataScenario {

    private final RedisTemplate<String, Object> template;

    public L501_04_GenericJacksonTypeMetadataScenario(RedisTemplate<String, Object> template) {
        this.template = template;
    }

    private static String key(String pageId) {
        return L501Keys.ACTIVITY_CARDS + pageId;
    }

    public void cacheHomeCards(String pageId, List<ActivityCard> cards) {
        template.opsForValue().set(key(pageId), cards);
    }

    @SuppressWarnings("unchecked")
    public List<ActivityCard> loadHomeCards(String pageId) {
        Object o = template.opsForValue().get(key(pageId));
        return (List<ActivityCard>) o;
    }

    public void evict(String pageId) {
        template.delete(key(pageId));
    }

    public static List<ActivityCard> sampleCards() {
        return Arrays.asList(
                new CouponCard("coupon-2026-618", "全场满 39 减 5", new BigDecimal("5.00"), new BigDecimal("39.00")),
                new BannerCard("banner-2026-618-top", "618 主会场", "https://cdn.x.com/banner/618.png"),
                new ProductCard("sku-100086", "经典美式", new BigDecimal("12.00"), 5_000)
        );
    }

    /**
     * 用 {@link JsonTypeInfo} 仅作示意——
     * 实际上 {@link org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer}
     * 默认是基于 enableDefaultTyping 写入 @class，无须我们在 POJO 上加注解。
     * 这里显式标注是为了如果你切换到 Jackson2JsonRedisSerializer + ObjectMapper 自定义类型信息时，行为依然正确。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
    public interface ActivityCard extends Serializable {
        String cardId();
        String cardType();
    }

    public static class CouponCard implements ActivityCard {
        private static final long serialVersionUID = 1L;
        private String couponId;
        private String title;
        private BigDecimal discount;
        private BigDecimal threshold;

        public CouponCard() {}
        public CouponCard(String couponId, String title, BigDecimal discount, BigDecimal threshold) {
            this.couponId = couponId; this.title = title;
            this.discount = discount; this.threshold = threshold;
        }
        @Override public String cardId()   { return couponId; }
        @Override public String cardType() { return "COUPON"; }

        public String getCouponId()        { return couponId; }
        public String getTitle()           { return title; }
        public BigDecimal getDiscount()    { return discount; }
        public BigDecimal getThreshold()   { return threshold; }
        public void setCouponId(String s)  { this.couponId = s; }
        public void setTitle(String s)     { this.title = s; }
        public void setDiscount(BigDecimal d)  { this.discount = d; }
        public void setThreshold(BigDecimal t) { this.threshold = t; }

        @Override public String toString() {
            return "CouponCard{" + couponId + "," + title + ",满" + threshold + "减" + discount + "}";
        }
    }

    public static class BannerCard implements ActivityCard {
        private static final long serialVersionUID = 1L;
        private String bannerId;
        private String title;
        private String imageUrl;

        public BannerCard() {}
        public BannerCard(String bannerId, String title, String imageUrl) {
            this.bannerId = bannerId; this.title = title; this.imageUrl = imageUrl;
        }
        @Override public String cardId()   { return bannerId; }
        @Override public String cardType() { return "BANNER"; }

        public String getBannerId()       { return bannerId; }
        public String getTitle()          { return title; }
        public String getImageUrl()       { return imageUrl; }
        public void setBannerId(String s) { this.bannerId = s; }
        public void setTitle(String s)    { this.title = s; }
        public void setImageUrl(String s) { this.imageUrl = s; }

        @Override public String toString() {
            return "BannerCard{" + bannerId + "," + title + "}";
        }
    }

    public static class ProductCard implements ActivityCard {
        private static final long serialVersionUID = 1L;
        private String skuId;
        private String name;
        private BigDecimal price;
        private Integer salesCount;

        public ProductCard() {}
        public ProductCard(String skuId, String name, BigDecimal price, Integer salesCount) {
            this.skuId = skuId; this.name = name;
            this.price = price; this.salesCount = salesCount;
        }
        @Override public String cardId()   { return skuId; }
        @Override public String cardType() { return "PRODUCT"; }

        public String getSkuId()              { return skuId; }
        public String getName()               { return name; }
        public BigDecimal getPrice()          { return price; }
        public Integer getSalesCount()        { return salesCount; }
        public void setSkuId(String s)        { this.skuId = s; }
        public void setName(String s)         { this.name = s; }
        public void setPrice(BigDecimal p)    { this.price = p; }
        public void setSalesCount(Integer i)  { this.salesCount = i; }

        @Override public String toString() {
            return "ProductCard{" + skuId + "," + name + "," + price + ",销量=" + salesCount + "}";
        }
    }
}
