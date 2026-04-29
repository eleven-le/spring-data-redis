package org.springframework.data.redis.springboot.laboratory.l7_10;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class L710FlashSaleService {

    private static final String CONFIG_FIELD_ROUND = "round";
    private static final String CONFIG_FIELD_TOKEN_RATE_LIMIT = "tokenRateLimit";
    private static final String CONFIG_FIELD_WINDOW_SECONDS = "windowSeconds";
    private static final String CONFIG_FIELD_STOCK = "preparedStock";

    private static final long TOKEN_GATE_ALLOWED = 1L;
    private static final long PURCHASE_SUCCESS = 0L;
    private static final long PURCHASE_INVALID_TOKEN = 1L;
    private static final long PURCHASE_DUPLICATE = 2L;
    private static final long PURCHASE_SOLD_OUT = 3L;

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> tokenGateScript;
    private final DefaultRedisScript<Long> purchaseScript;
    private final L710OrderPipeline orderPipeline;
    private final L710FlashSaleProperties properties;

    private final AtomicBoolean trafficGateOpen = new AtomicBoolean(true);
    private final Set<Long> soldOutLocally = ConcurrentHashMap.newKeySet();

    public L710FlashSaleService(StringRedisTemplate stringRedisTemplate,
                                DefaultRedisScript<Long> tokenGateScript,
                                DefaultRedisScript<Long> purchaseScript,
                                L710OrderPipeline orderPipeline,
                                L710FlashSaleProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.tokenGateScript = tokenGateScript;
        this.purchaseScript = purchaseScript;
        this.orderPipeline = orderPipeline;
        this.properties = properties;
    }

    public L710PrepareResponse prepareSale(long skuId, int stock, int tokenRateLimit, int windowSeconds) {
        Long round = this.stringRedisTemplate.opsForValue().increment(L710FlashSaleKeys.roundKey(skuId));
        if (round == null) {
            throw new IllegalStateException("Failed to allocate flash-sale round.");
        }

        String configKey = L710FlashSaleKeys.configKey(skuId);
        String stockKey = L710FlashSaleKeys.stockKey(skuId, round);
        Map<String, String> configMap = new LinkedHashMap<String, String>();
        configMap.put(CONFIG_FIELD_ROUND, Long.toString(round));
        configMap.put(CONFIG_FIELD_TOKEN_RATE_LIMIT, Integer.toString(tokenRateLimit));
        configMap.put(CONFIG_FIELD_WINDOW_SECONDS, Integer.toString(windowSeconds));
        configMap.put(CONFIG_FIELD_STOCK, Integer.toString(stock));

        this.stringRedisTemplate.opsForHash().putAll(configKey, configMap);
        this.stringRedisTemplate.opsForValue().set(stockKey, Integer.toString(stock));
        this.soldOutLocally.remove(skuId);

        return new L710PrepareResponse(skuId, round, stock, tokenRateLimit, windowSeconds, Instant.now());
    }

    public L710TokenResponse issuePurchaseToken(long skuId, String userId) {
        if (!this.trafficGateOpen.get()) {
            return L710TokenResponse.rejected(skuId, "TRAFFIC_GATE_CLOSED", "L7-10 traffic gate is closed.");
        }

        L710SaleConfig config = this.loadConfig(skuId);
        if (config == null) {
            return L710TokenResponse.rejected(skuId, "SALE_NOT_PREPARED", "Prepare the SKU before issuing tokens.");
        }

        long stockSnapshot = this.currentStock(config);
        if (stockSnapshot < 0) {
            return L710TokenResponse.rejected(skuId, "STOCK_UNAVAILABLE", "Cannot load stock snapshot from Redis.");
        }

        if (this.soldOutLocally.contains(skuId) || stockSnapshot == 0) {
            this.soldOutLocally.add(skuId);
            return L710TokenResponse.rejected(skuId, "SOLD_OUT_FAST_FAIL", "Local sold-out short circuit is active.");
        }

        Long gateResult = this.stringRedisTemplate.execute(this.tokenGateScript,
                Arrays.asList(L710FlashSaleKeys.rateLimitKey(skuId, config.getRound())),
                Integer.toString(config.getWindowSeconds()),
                Integer.toString(config.getTokenRateLimit()));

        if (!Long.valueOf(TOKEN_GATE_ALLOWED).equals(gateResult)) {
            return L710TokenResponse.rejected(skuId, "TOKEN_GATE_REJECTED", "Token endpoint is rate-limited.");
        }

        String token = UUID.randomUUID().toString();
        this.stringRedisTemplate.opsForValue().set(
                L710FlashSaleKeys.tokenKey(skuId, config.getRound(), userId),
                token,
                Duration.ofSeconds(this.properties.getTokenTtlSeconds()));

        return L710TokenResponse.accepted(skuId, config.getRound(), userId, token, stockSnapshot);
    }

    public L710PurchaseResponse purchase(long skuId, String userId, String token) {
        if (!this.trafficGateOpen.get()) {
            return L710PurchaseResponse.rejected(skuId, -1L, "TRAFFIC_GATE_CLOSED", "Traffic gate is closed.");
        }

        L710SaleConfig config = this.loadConfig(skuId);
        if (config == null) {
            return L710PurchaseResponse.rejected(skuId, -1L, "SALE_NOT_PREPARED", "Prepare the SKU before purchase.");
        }

        if (this.soldOutLocally.contains(skuId)) {
            return L710PurchaseResponse.rejected(skuId, config.getRound(), "SOLD_OUT_FAST_FAIL", "Local sold-out cache rejected the request.");
        }

        String orderNo = buildOrderNo(skuId, config.getRound(), userId);
        Long result = this.stringRedisTemplate.execute(this.purchaseScript,
                Arrays.asList(
                        L710FlashSaleKeys.tokenKey(skuId, config.getRound(), userId),
                        L710FlashSaleKeys.orderKey(skuId, config.getRound(), userId),
                        L710FlashSaleKeys.stockKey(skuId, config.getRound())
                ),
                token,
                orderNo,
                Integer.toString(this.properties.getPurchaseRecordTtlSeconds()));

        if (Long.valueOf(PURCHASE_SUCCESS).equals(result)) {
            this.orderPipeline.submit(new L710OrderPipeline.L710OrderTask(orderNo, skuId, config.getRound(), userId));
            long remainingStock = this.currentStock(config);
            if (remainingStock == 0) {
                this.soldOutLocally.add(skuId);
            }
            return L710PurchaseResponse.accepted(orderNo, skuId, config.getRound(), remainingStock);
        }

        if (Long.valueOf(PURCHASE_INVALID_TOKEN).equals(result)) {
            return L710PurchaseResponse.rejected(skuId, config.getRound(), "INVALID_TOKEN", "Token is missing, expired, or mismatched.");
        }

        if (Long.valueOf(PURCHASE_DUPLICATE).equals(result)) {
            return L710PurchaseResponse.rejected(skuId, config.getRound(), "DUPLICATE_PURCHASE", "This user already placed an order.");
        }

        if (Long.valueOf(PURCHASE_SOLD_OUT).equals(result)) {
            this.soldOutLocally.add(skuId);
            return L710PurchaseResponse.rejected(skuId, config.getRound(), "SOLD_OUT", "Stock is exhausted.");
        }

        return L710PurchaseResponse.rejected(skuId, config.getRound(), "UNKNOWN", "Unexpected purchase script result: " + result);
    }

    public L710DashboardResponse dashboard(long skuId) {
        L710SaleConfig config = this.loadConfig(skuId);
        long round = config == null ? -1L : config.getRound();
        long stock = config == null ? -1L : this.currentStock(config);
        return new L710DashboardResponse(
                skuId,
                round,
                stock,
                this.trafficGateOpen.get(),
                this.soldOutLocally.contains(skuId),
                this.orderPipeline.getQueueSize(),
                this.orderPipeline.getOrdersForSku(skuId));
    }

    public L710GateResponse changeTrafficGate(boolean enabled) {
        this.trafficGateOpen.set(enabled);
        return new L710GateResponse(enabled, Instant.now());
    }

    private L710SaleConfig loadConfig(long skuId) {
        Map<Object, Object> raw = this.stringRedisTemplate.opsForHash().entries(L710FlashSaleKeys.configKey(skuId));
        if (raw == null || raw.isEmpty()) {
            return null;
        }

        try {
            return new L710SaleConfig(
                    skuId,
                    Long.parseLong((String) raw.get(CONFIG_FIELD_ROUND)),
                    Integer.parseInt((String) raw.get(CONFIG_FIELD_TOKEN_RATE_LIMIT)),
                    Integer.parseInt((String) raw.get(CONFIG_FIELD_WINDOW_SECONDS))
            );
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid flash-sale config for sku " + skuId, ex);
        }
    }

    private long currentStock(L710SaleConfig config) {
        try {
            String value = this.stringRedisTemplate.opsForValue().get(L710FlashSaleKeys.stockKey(config.getSkuId(), config.getRound()));
            if (value == null) {
                return -1L;
            }
            return Long.parseLong(value);
        } catch (DataAccessException ex) {
            return -1L;
        }
    }

    private static String buildOrderNo(long skuId, long round, String userId) {
        return "FS-" + skuId + "-" + round + "-" + userId + "-" + System.currentTimeMillis();
    }

    private static final class L710SaleConfig {

        private final long skuId;
        private final long round;
        private final int tokenRateLimit;
        private final int windowSeconds;

        private L710SaleConfig(long skuId, long round, int tokenRateLimit, int windowSeconds) {
            this.skuId = skuId;
            this.round = round;
            this.tokenRateLimit = tokenRateLimit;
            this.windowSeconds = windowSeconds;
        }

        public long getSkuId() {
            return this.skuId;
        }

        public long getRound() {
            return this.round;
        }

        public int getTokenRateLimit() {
            return this.tokenRateLimit;
        }

        public int getWindowSeconds() {
            return this.windowSeconds;
        }
    }

    public static class L710PrepareResponse {

        private final long skuId;
        private final long round;
        private final int stock;
        private final int tokenRateLimit;
        private final int windowSeconds;
        private final Instant preparedAt;

        public L710PrepareResponse(long skuId, long round, int stock, int tokenRateLimit,
                                   int windowSeconds, Instant preparedAt) {
            this.skuId = skuId;
            this.round = round;
            this.stock = stock;
            this.tokenRateLimit = tokenRateLimit;
            this.windowSeconds = windowSeconds;
            this.preparedAt = preparedAt;
        }

        public long getSkuId() {
            return this.skuId;
        }

        public long getRound() {
            return this.round;
        }

        public int getStock() {
            return this.stock;
        }

        public int getTokenRateLimit() {
            return this.tokenRateLimit;
        }

        public int getWindowSeconds() {
            return this.windowSeconds;
        }

        public Instant getPreparedAt() {
            return this.preparedAt;
        }
    }

    public static class L710TokenResponse {

        private final boolean accepted;
        private final String code;
        private final String message;
        private final long skuId;
        private final long round;
        private final String userId;
        private final String token;
        private final long stockSnapshot;

        public L710TokenResponse(boolean accepted, String code, String message, long skuId,
                                 long round, String userId, String token, long stockSnapshot) {
            this.accepted = accepted;
            this.code = code;
            this.message = message;
            this.skuId = skuId;
            this.round = round;
            this.userId = userId;
            this.token = token;
            this.stockSnapshot = stockSnapshot;
        }

        static L710TokenResponse accepted(long skuId, long round, String userId, String token, long stockSnapshot) {
            return new L710TokenResponse(true, "TOKEN_READY", "Purchase token issued.",
                    skuId, round, userId, token, stockSnapshot);
        }

        static L710TokenResponse rejected(long skuId, String code, String message) {
            return new L710TokenResponse(false, code, message, skuId, -1L, null, null, -1L);
        }

        public boolean isAccepted() {
            return this.accepted;
        }

        public String getCode() {
            return this.code;
        }

        public String getMessage() {
            return this.message;
        }

        public long getSkuId() {
            return this.skuId;
        }

        public long getRound() {
            return this.round;
        }

        public String getUserId() {
            return this.userId;
        }

        public String getToken() {
            return this.token;
        }

        public long getStockSnapshot() {
            return this.stockSnapshot;
        }
    }

    public static class L710PurchaseResponse {

        private final boolean accepted;
        private final String code;
        private final String message;
        private final String orderNo;
        private final long skuId;
        private final long round;
        private final long remainingStock;

        public L710PurchaseResponse(boolean accepted, String code, String message,
                                    String orderNo, long skuId, long round, long remainingStock) {
            this.accepted = accepted;
            this.code = code;
            this.message = message;
            this.orderNo = orderNo;
            this.skuId = skuId;
            this.round = round;
            this.remainingStock = remainingStock;
        }

        static L710PurchaseResponse accepted(String orderNo, long skuId, long round, long remainingStock) {
            return new L710PurchaseResponse(true, "ORDER_ACCEPTED",
                    "Redis accepted the purchase and queued the order.",
                    orderNo, skuId, round, remainingStock);
        }

        static L710PurchaseResponse rejected(long skuId, long round, String code, String message) {
            return new L710PurchaseResponse(false, code, message, null, skuId, round, -1L);
        }

        public boolean isAccepted() {
            return this.accepted;
        }

        public String getCode() {
            return this.code;
        }

        public String getMessage() {
            return this.message;
        }

        public String getOrderNo() {
            return this.orderNo;
        }

        public long getSkuId() {
            return this.skuId;
        }

        public long getRound() {
            return this.round;
        }

        public long getRemainingStock() {
            return this.remainingStock;
        }
    }

    public static class L710DashboardResponse {

        private final long skuId;
        private final long round;
        private final long remainingStock;
        private final boolean trafficGateOpen;
        private final boolean soldOutLocally;
        private final int orderQueueSize;
        private final List<L710OrderPipeline.L710OrderRecord> orders;

        public L710DashboardResponse(long skuId, long round, long remainingStock, boolean trafficGateOpen,
                                     boolean soldOutLocally, int orderQueueSize,
                                     List<L710OrderPipeline.L710OrderRecord> orders) {
            this.skuId = skuId;
            this.round = round;
            this.remainingStock = remainingStock;
            this.trafficGateOpen = trafficGateOpen;
            this.soldOutLocally = soldOutLocally;
            this.orderQueueSize = orderQueueSize;
            this.orders = orders;
        }

        public long getSkuId() {
            return this.skuId;
        }

        public long getRound() {
            return this.round;
        }

        public long getRemainingStock() {
            return this.remainingStock;
        }

        public boolean isTrafficGateOpen() {
            return this.trafficGateOpen;
        }

        public boolean isSoldOutLocally() {
            return this.soldOutLocally;
        }

        public int getOrderQueueSize() {
            return this.orderQueueSize;
        }

        public List<L710OrderPipeline.L710OrderRecord> getOrders() {
            return this.orders;
        }
    }

    public static class L710GateResponse {

        private final boolean enabled;
        private final Instant changedAt;

        public L710GateResponse(boolean enabled, Instant changedAt) {
            this.enabled = enabled;
            this.changedAt = changedAt;
        }

        public boolean isEnabled() {
            return this.enabled;
        }

        public Instant getChangedAt() {
            return this.changedAt;
        }
    }
}
