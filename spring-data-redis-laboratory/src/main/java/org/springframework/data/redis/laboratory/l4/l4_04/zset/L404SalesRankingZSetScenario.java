package org.springframework.data.redis.laboratory.l4.l4_04.zset;

import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 首页实时销量 Top N 榜：member = 商品 ID，score = 当日销量。
 * <p>
 * 真实业务认知：
 * 1) Redis 只是"实时榜单"载体，订单事实源永远在订单库 / OLAP。每天定时把 Redis 销量回灌到 DB 兜底。
 * 2) 高 QPS 同一商品 incrementScore 会形成"同 key 串行写"，热点商品的写吞吐受限于单分片单线程。
 * 解法：分桶（item:{id}:bucket{0..N}）+ 读时 union 合并；或上聚合层。
 * 3) 榜单按天滚动，必须设过期时间。
 * 4) 不要跨天读 Redis 取历史榜单，历史榜单走 DB / 数仓。
 */
public class L404SalesRankingZSetScenario {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration TTL = Duration.ofDays(2); // 当日 + 1 天容错

    private final StringRedisTemplate template;

    public L404SalesRankingZSetScenario(StringRedisTemplate template) {
        this.template = template;
    }

    private BoundZSetOperations<String, String> rank() {
        String key = L404Keys.SALES_RANK + LocalDate.now().format(DAY_FMT);
        BoundZSetOperations<String, String> b = template.boundZSetOps(key);
        b.expire(TTL);
        return b;
    }

    /**
     * 销量累加。delta 一般是当次订单中商品数量。
     */
    public Double increaseSales(String itemId, double delta) {
        return rank().incrementScore(itemId, delta);
        // 断点: DefaultBoundZSetOperations.incrementScore → DefaultZSetOperations.incrementScore
    }

    /**
     * 取 Top N（带 score 给前端展示销量）。
     */
    public Set<TypedTuple<String>> getTopN(int n) {
        return rank().reverseRangeWithScores(0, n - 1);
    }

    /**
     * 商品当前排名（1 表示榜首；查不到返回 null）。
     */
    public Long getItemRank(String itemId) {
        Long rank = rank().reverseRank(itemId);
        return rank == null ? null : rank + 1;
    }

    public Double getItemScore(String itemId) {
        return rank().score(itemId);
    }

    public void clearTodayRank() {
        template.delete(L404Keys.SALES_RANK + LocalDate.now().format(DAY_FMT));
    }
}
