package org.springframework.data.redis.laboratory.l4.l4_03.hash;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.util.Map;
import java.util.Set;

/**
 * 用户行为状态：是否点赞 / 收藏 / 关注。
 * <pre>
 *   key   = l4:03:user:behavior:{userId}
 *   field = liked:{itemId} / fav:{itemId} / follow:{targetUserId}
 *   value = "1"
 * </pre>
 * <p>
 * Hash 能这么用，但要警惕 field 数量膨胀：
 * <ul>
 *   <li>头部用户点赞过 10w 商品 → 单 Hash 有 10w field，HGETALL/HKEYS 直接拖死；</li>
 *   <li>这种"用户 × 海量对象"的关联关系，更适合 Redis Set / Bitmap / 反向索引（item -> users）；</li>
 *   <li>本类只演示最基础的写法，业务真接入前请评估 field 上限。</li>
 * </ul>
 */
public class L403UserBehaviorHashScenario {

    private final HashOperations<String, String, Object> ops;
    private final RedisTemplate<String, Object> template;

    public L403UserBehaviorHashScenario(RedisTemplate<String, Object> template) {
        this.template = template;
        this.ops = template.opsForHash();
    }

    private static String key(String userId) {
        return L403Keys.USER_BEHAV + userId;
    }

    private static String likeField(String itemId) {
        return "liked:" + itemId;
    }

    private static String favField(String itemId) {
        return "fav:" + itemId;
    }

    private static String followField(String targetId) {
        return "follow:" + targetId;
    }

    public void like(String userId, String itemId) {
        ops.put(key(userId), likeField(itemId), "1");
    }

    public void unlike(String userId, String itemId) {
        ops.delete(key(userId), likeField(itemId));
    }

    public boolean hasLiked(String userId, String itemId) {
        return Boolean.TRUE.equals(ops.hasKey(key(userId), likeField(itemId)));
    }

    public void favorite(String userId, String itemId) {
        ops.put(key(userId), favField(itemId), "1");
    }

    public void follow(String userId, String targetId) {
        ops.put(key(userId), followField(targetId), "1");
    }

    /**
     * 仅为教学暴露：真实业务严禁对头部用户调这个。
     */
    public Set<String> debugListAllFields(String userId) {
        return ops.keys(key(userId));
    }

    public Map<String, Object> debugDump(String userId) {
        return template.<String, Object>opsForHash().entries(key(userId));
    }
}
