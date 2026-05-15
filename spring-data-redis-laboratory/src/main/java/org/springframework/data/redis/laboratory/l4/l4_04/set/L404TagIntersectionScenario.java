package org.springframework.data.redis.laboratory.l4.l4_04.set;

import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签人群运算：每个 tag 一个 Set，存属于该 tag 的 userId。
 * 推荐 / 营销 / 风控里的"轻量人群圈选"——SINTER / SUNION / SDIFF 一把就行。
 * <p>
 * 适用边界：
 * 1) 单 tag Set 在 100 万元素以下，命中查询和交并差还能在线跑。
 * 2) 千万 / 亿级人群运算放 Redis 是灾难，应当 Spark / Flink / Doris 离线算好结果集，再回灌到 Redis 缓存。
 * 3) Redis 7+ 的 SINTERCARD 只返回交集大小，省去序列化整个交集的成本。
 * 4) 大集合 SINTER 会阻塞主线程（Redis 单线程），生产请避开请求高峰；或者 sintersStore 写入后台 key，再分页 SSCAN。
 */
public class L404TagIntersectionScenario {

    private final SetOperations<String, String> ops;

    public L404TagIntersectionScenario(StringRedisTemplate template) {
        this.ops = template.opsForSet();
    }

    private String key(String tagId) {
        return L404Keys.TAG_USERS + tagId;
    }

    public void addUserToTag(String tagId, String userId) {
        ops.add(key(tagId), userId);
    }

    /**
     * 共同人群（同时打了 A、B 两个标签的用户）。
     */
    public Set<String> commonUsers(String tagA, String tagB) {
        return ops.intersect(key(tagA), key(tagB));
        // 断点: DefaultSetOperations.intersect → connection.setCommands().sInter
    }

    /**
     * 合并人群（任一标签命中）。
     */
    public Set<String> unionUsers(String tagA, String tagB) {
        return ops.union(key(tagA), key(tagB));
    }

    /**
     * 排除人群（在 A 但不在 B）。
     */
    public Set<String> diffUsers(String tagA, String tagB) {
        return ops.difference(key(tagA), key(tagB));
    }

    /**
     * 多 tag 交集。
     */
    public Set<String> commonAcross(String... tagIds) {
        return ops.intersect(Arrays.stream(tagIds).map(this::key).collect(Collectors.toList()));
    }
}
