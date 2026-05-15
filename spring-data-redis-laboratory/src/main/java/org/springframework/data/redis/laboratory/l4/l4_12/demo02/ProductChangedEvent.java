package org.springframework.data.redis.laboratory.l4.l4_12.demo02;

/**
 * 商品变更事件——只放"业务 key + 版本 + 变更类型 + 链路追踪信息"，绝不放完整商品对象。
 *
 * <p>生产经验：Pub/Sub 消息体不可信、不可重放，只能当"通知 + 提示去回查主数据"。
 * 因此这里的字段全是定位信息，不是数据快照。
 */
public class ProductChangedEvent {

    public enum ChangeType {
        PRICE,        // 价格变更
        STOCK_DISPLAY,// 库存展示文案变更（不是真实库存扣减）
        ON_OFF_SHELF, // 上下架
        TAG           // 营销标签
    }

    private long productId;
    private long shopId;
    private ChangeType changeType;
    /** 主数据当前版本号——消费端用来做幂等与乱序保护 */
    private long version;
    private long eventTime;
    private String traceId;

    public ProductChangedEvent() {
    }

    public ProductChangedEvent(long productId, long shopId, ChangeType changeType,
                               long version, long eventTime, String traceId) {
        this.productId = productId;
        this.shopId = shopId;
        this.changeType = changeType;
        this.version = version;
        this.eventTime = eventTime;
        this.traceId = traceId;
    }

    public long getProductId() { return productId; }
    public void setProductId(long productId) { this.productId = productId; }

    public long getShopId() { return shopId; }
    public void setShopId(long shopId) { this.shopId = shopId; }

    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public long getEventTime() { return eventTime; }
    public void setEventTime(long eventTime) { this.eventTime = eventTime; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    @Override
    public String toString() {
        return "ProductChangedEvent{productId=" + productId + ", shopId=" + shopId +
                ", changeType=" + changeType + ", version=" + version +
                ", traceId='" + traceId + "'}";
    }
}
