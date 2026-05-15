package org.springframework.data.redis.laboratory.l4.l4_04.zset;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 内容热榜：score = 加权热度分（点赞、评论、收藏、浏览的加权和）。
 * <p>
 * 设计要点：
 * 1) score 复杂公式（含时间衰减、品类权重）建议在应用层算完再写入；ZINCRBY 只能做线性累加。
 * 2) 滚动窗口：每小时 / 每天一个 key，旧 key 自然过期，新 key 自然继承新数据。
 * 3) 作弊和刷榜不是 Redis 能解决的，要风控前置 + 反作弊系统兜底。
 * 4) 大流量"上热门" 风暴会导致单 key 写热点，热门话题分桶；读时 ZUNIONSTORE 合并。
 */
public class L404HotContentZSetScenario {

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final Duration TTL = Duration.ofHours(3); // 当前小时 + 缓冲

    private final StringRedisTemplate template;
    private final ZSetOperations<String, String> ops;

    public L404HotContentZSetScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForZSet();
    }

    private String hotKey() {
        return L404Keys.HOT_CONTENT + LocalDateTime.now().format(HOUR_FMT);
    }

    /**
     * 简单线性热度增量；如果是复合公式（如 0.7*like + 0.2*comment + 衰减），请改在调用方算好 finalScore 再传 add(...)。
     */
    public Double increaseHotScore(String contentId, double delta) {
        String key = hotKey();
        Double newScore = ops.incrementScore(key, contentId, delta);
        template.expire(key, TTL);
        return newScore;
    }

    public Set<TypedTuple<String>> getHotTopN(int n) {
        return ops.reverseRangeWithScores(hotKey(), 0, n - 1);
    }

    public Double getContentScore(String contentId) {
        return ops.score(hotKey(), contentId);
    }

    public Long removeContent(String contentId) {
        return ops.remove(hotKey(), contentId);
    }
}
