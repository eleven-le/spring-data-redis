package org.springframework.data.redis.springboot.laboratory.l7_10;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lab/l7-10")
public class L710FlashSaleController {

    private final L710FlashSaleService flashSaleService;

    public L710FlashSaleController(L710FlashSaleService flashSaleService) {
        this.flashSaleService = flashSaleService;
    }

    @PostMapping("/admin/skus/{skuId}/prepare")
    public L710FlashSaleService.L710PrepareResponse prepare(@PathVariable long skuId,
                                                            @RequestBody L710PrepareRequest request) {
        return this.flashSaleService.prepareSale(
                skuId,
                request.getStock(),
                request.getTokenRateLimit(),
                request.getWindowSeconds());
    }

    @PostMapping("/admin/traffic-gate")
    public L710FlashSaleService.L710GateResponse trafficGate(@RequestBody L710GateRequest request) {
        return this.flashSaleService.changeTrafficGate(request.isEnabled());
    }

    @PostMapping("/skus/{skuId}/token")
    public L710FlashSaleService.L710TokenResponse token(@PathVariable long skuId,
                                                        @RequestParam String userId) {
        return this.flashSaleService.issuePurchaseToken(skuId, userId);
    }

    @PostMapping("/skus/{skuId}/purchase")
    public L710FlashSaleService.L710PurchaseResponse purchase(@PathVariable long skuId,
                                                              @RequestBody L710PurchaseRequest request) {
        return this.flashSaleService.purchase(skuId, request.getUserId(), request.getToken());
    }

    @GetMapping("/skus/{skuId}/dashboard")
    public L710FlashSaleService.L710DashboardResponse dashboard(@PathVariable long skuId) {
        return this.flashSaleService.dashboard(skuId);
    }

    public static class L710PrepareRequest {

        private int stock;
        private int tokenRateLimit;
        private int windowSeconds;

        public int getStock() {
            return this.stock;
        }

        public void setStock(int stock) {
            this.stock = stock;
        }

        public int getTokenRateLimit() {
            return this.tokenRateLimit;
        }

        public void setTokenRateLimit(int tokenRateLimit) {
            this.tokenRateLimit = tokenRateLimit;
        }

        public int getWindowSeconds() {
            return this.windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }
    }

    public static class L710GateRequest {

        private boolean enabled;

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class L710PurchaseRequest {

        private String userId;
        private String token;

        public String getUserId() {
            return this.userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getToken() {
            return this.token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
