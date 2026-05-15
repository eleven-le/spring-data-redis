package org.springframework.data.redis.laboratory.l4.l4_04.set;

import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.util.Set;

/**
 * 点赞用户集合：Set 存"谁点了赞"，幂等（重复点赞自动去重）。
 * <p>
 * 性能边界：
 * 1) 热点内容点赞数 100 万+，Set 单 key 内存几十 MB，不要在请求路径上 SCARD（虽然是 O(1) 但经过网络）。
 * 2) 高 QPS 点赞计数请用 String INCR 单独维护，Set 只保留是否点赞的明细。
 * 3) 真要查"点赞用户列表"，请用 ZSet（score=点赞时间）支持时间排序+分页，比 Set + 业务排序高效得多。
 * 4) 大 Set 不要直接 SMEMBERS，用 SSCAN。
 */
public class L404LikeSetScenario {

    private final SetOperations<String, String> ops;
    private final StringRedisTemplate template;

    public L404LikeSetScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForSet();
    }

    private String key(String itemId) {
        return L404Keys.LIKE_USERS + itemId;
    }

    /**
     * 点赞，返回 true 表示首次点赞（这次真的加一）。
     */
    public boolean like(String itemId, String userId) {
        Long added = ops.add(key(itemId), userId);
        return added != null && added > 0;
    }

    /**
     * 取消点赞。
     */
    public boolean unlike(String itemId, String userId) {
        Long removed = ops.remove(key(itemId), userId);
        return removed != null && removed > 0;
    }

    public Boolean hasLiked(String itemId, String userId) {
        return ops.isMember(key(itemId), userId);
    }

    /**
     * 仅适合非热点查询；热点请走单独的 String Counter。
     */
    public Long countLikes(String itemId) {
        return ops.size(key(itemId));
    }

    /**
     * 调试用。生产严禁对热点 item 直接 SMEMBERS。
     */
    public Set<String> getLikeUsers(String itemId) {
        return ops.members(key(itemId));
    }
}
