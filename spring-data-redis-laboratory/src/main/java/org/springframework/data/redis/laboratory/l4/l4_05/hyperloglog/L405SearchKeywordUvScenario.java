package org.springframework.data.redis.laboratory.l4.l4_05.hyperloglog;

import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_05.L405Keys;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;

/**
 * 场景 3.3：搜索关键词独立用户数。
 * <p>
 * key：{@code l4:05:hll:uv:search:{keywordHash}:{yyyyMMdd}}
 * <p>
 * 为什么 keyword 要 hash：
 * <ul>
 *   <li>关键词可能含空格、表情、长 URL，直接放 key 会污染命名空间</li>
 *   <li>极长 keyword 撑大 key 内存</li>
 *   <li>hash 化稳定后，和离线数仓做反查时只需把 hash → keyword 字典维护好</li>
 * </ul>
 * <p>
 * 边界：热门搜索词的"独立用户数"足够用 HLL；但"哪些用户搜了哪些词"必须落日志，HLL 拿不回明细。
 */
public class L405SearchKeywordUvScenario {

    public static final Duration TTL = Duration.ofDays(60);

    private final StringRedisTemplate template;
    private final HyperLogLogOperations<String, String> hll;

    public L405SearchKeywordUvScenario(StringRedisTemplate template) {
        this.template = template;
        this.hll = template.opsForHyperLogLog();
    }

    public void recordSearch(String keyword, String userId) {
        recordSearch(keyword, userId, LocalDate.now());
    }

    public void recordSearch(String keyword, String userId, LocalDate date) {
        String key = L405Keys.searchKeywordUv(hashKeyword(keyword), date);
        hll.add(key, userId);
        template.expire(key, TTL);
    }

    public long getKeywordUv(String keyword, LocalDate date) {
        Long c = hll.size(L405Keys.searchKeywordUv(hashKeyword(keyword), date));
        return c == null ? 0 : c;
    }

    public void clearKeywordUv(String keyword, LocalDate date) {
        template.delete(L405Keys.searchKeywordUv(hashKeyword(keyword), date));
    }

    /** 用 SHA-256 截取前 16 字符作为短 hash，足够避免碰撞（业务非加密用途）。 */
    public static String hashKeyword(String keyword) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(keyword.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
