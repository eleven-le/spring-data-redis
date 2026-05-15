package org.springframework.data.redis.laboratory.l4.l4_03.hash;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户资料缓存场景：
 * <pre>
 *   key   = l4:03:user:profile:{userId}
 *   field = nickname / avatar / gender / level / city / lastLoginTime
 * </pre>
 * <p>
 * 为什么 Hash 比 String JSON 更适合？
 * <ul>
 *   <li>改昵称只需 HSET nickname，不必 GET → 反序列化 → 改 → 序列化 → SET 整张 JSON；</li>
 *   <li>读"等级 + 城市"只需 HMGET 两个 field，不必把头像、最近登录时间等无关字段也读出来；</li>
 *   <li>HINCRBY 可对积分等字段做服务端原子加。</li>
 * </ul>
 * <p>
 * Hash 不适合什么？
 * <ul>
 *   <li>字段无限增长（如把所有粉丝塞成 field），底层从 ziplist 退化到 hashtable，内存与扫描成本飙升；</li>
 *   <li>需要 field 级 TTL —— Redis Hash 的 TTL 是整 key 级别，不能按 field 单独过期。</li>
 * </ul>
 */
public class L403UserProfileHashScenario {

    public static final Duration PROFILE_TTL = Duration.ofHours(2);

    private final HashOperations<String, String, Object> ops;
    private final RedisTemplate<String, Object> template;

    public L403UserProfileHashScenario(RedisTemplate<String, Object> template) {
        this.template = template;
        this.ops = template.opsForHash();
    }

    private static String key(String userId) {
        return L403Keys.USER_PROFILE + userId;
    }

    /**
     * 全量写入或重建 —— 一次 HMSET。
     */
    public void saveProfile(String userId, Map<String, Object> profile) {
        String k = key(userId);
        ops.putAll(k, profile);
        template.expire(k, PROFILE_TTL);
    }

    /**
     * 局部更新 —— 一次 HSET，不必读旧数据。这是 Hash 相对 String JSON 的核心优势。
     */
    public void updateNickname(String userId, String nickname) {
        ops.put(key(userId), "nickname", nickname);
    }

    /**
     * 全字段读取 —— HGETALL。字段多时考虑 getPartialProfile。
     */
    public Map<String, Object> getProfile(String userId) {
        return template.<String, Object>opsForHash().entries(key(userId));
    }

    /**
     * 部分字段读取 —— HMGET，N 个字段一次往返。
     */
    public Map<String, Object> getPartialProfile(String userId, List<String> fields) {
        List<Object> values = ops.multiGet(key(userId), fields);
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            result.put(fields.get(i), values.get(i));
        }
        return result;
    }

    public void deleteProfile(String userId) {
        template.delete(key(userId));
    }
}
