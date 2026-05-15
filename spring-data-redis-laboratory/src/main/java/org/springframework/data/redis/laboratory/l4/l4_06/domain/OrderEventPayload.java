package org.springframework.data.redis.laboratory.l4.l4_06.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 06.03 消息体设计
 *
 * 真实订单事件载荷。比"orderId+userId"复杂得多，
 * 给后端 5 个 group（库存/优惠券/通知/风控/埋点）足够的上下文：
 *  - 库存预占需要 skuList；
 *  - 优惠券核销需要 couponIds + 抵扣金额；
 *  - 通知需要 channel + templateCode；
 *  - 风控需要 IP/deviceId/payAmount；
 *  - 埋点需要 source/clientVer/utmSource。
 *
 * 为什么 payload 结构化而不是 String：
 *  - C 端业务事件字段必然多，编辑 String key=value 极易拼错；
 *  - JSON 化后可以做 schema 演进、字段灰度兼容；
 *  - 但对外仍然只在 stream 里塞一个 "payload" 字段（JSON 字符串），
 *    避免事件结构爆炸把 redis-cli XRANGE 变成不可读，详见生产者注释。
 *
 * 新手避坑：
 *  - 不要把"全部订单详情"塞进 payload —— Redis 不是消息系统的事实源；
 *  - 大对象建议放 DB，事件里只塞 ID 让消费者反查；
 *  - 字段命名跟 DB 列对齐，避免消费者要做映射。
 */
public class OrderEventPayload {

    private List<SkuLine> skuLines;
    private List<String> couponIds;
    private BigDecimal payAmount;
    private BigDecimal couponDiscountAmount;
    private String payChannel;
    private String clientIp;
    private String deviceId;
    private String clientVer;
    private String utmSource;

    public OrderEventPayload() {
    }

    public List<SkuLine> getSkuLines() {
        return skuLines;
    }

    public void setSkuLines(List<SkuLine> skuLines) {
        this.skuLines = skuLines;
    }

    public List<String> getCouponIds() {
        return couponIds;
    }

    public void setCouponIds(List<String> couponIds) {
        this.couponIds = couponIds;
    }

    public BigDecimal getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(BigDecimal payAmount) {
        this.payAmount = payAmount;
    }

    public BigDecimal getCouponDiscountAmount() {
        return couponDiscountAmount;
    }

    public void setCouponDiscountAmount(BigDecimal couponDiscountAmount) {
        this.couponDiscountAmount = couponDiscountAmount;
    }

    public String getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(String payChannel) {
        this.payChannel = payChannel;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getClientVer() {
        return clientVer;
    }

    public void setClientVer(String clientVer) {
        this.clientVer = clientVer;
    }

    public String getUtmSource() {
        return utmSource;
    }

    public void setUtmSource(String utmSource) {
        this.utmSource = utmSource;
    }

    public static class SkuLine {
        private String skuId;
        private int quantity;
        private BigDecimal salePrice;

        public SkuLine() {
        }

        public SkuLine(String skuId, int quantity, BigDecimal salePrice) {
            this.skuId = skuId;
            this.quantity = quantity;
            this.salePrice = salePrice;
        }

        public String getSkuId() {
            return skuId;
        }

        public void setSkuId(String skuId) {
            this.skuId = skuId;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        public BigDecimal getSalePrice() {
            return salePrice;
        }

        public void setSalePrice(BigDecimal salePrice) {
            this.salePrice = salePrice;
        }
    }
}
