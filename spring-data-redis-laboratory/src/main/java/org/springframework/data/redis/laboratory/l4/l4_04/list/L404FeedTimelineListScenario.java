package org.springframework.data.redis.laboratory.l4.l4_04.list;

import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.util.List;

/**
 * 简化版 Feed 时间线：每个用户一个 List，存最近 N 条内容 ID。
 * <p>
 * 这只是教学模型，距离真实大厂 Feed 系统差了几条街：
 * 1) push 模式（写扩散）：发一条内容要往 N 个粉丝的收件箱写 N 次，关注关系大的"头部用户"会写爆。
 * 2) pull 模式（读扩散）：读一次拉 K 个关注对象的发件箱，然后做合并排序。
 * 3) 真实系统通常 push + pull 混合 + 离线分桶 + 推荐排序。
 * 4) Redis List 在这里只承担"收件箱缓存"的角色，事实源在数据库 / Kafka 流。
 */
public class L404FeedTimelineListScenario {

    private static final int MAX_TIMELINE_SIZE = 500;

    private final StringRedisTemplate template;
    private final ListOperations<String, String> ops;

    public L404FeedTimelineListScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForList();
    }

    public void pushFeed(String userId, String contentId) {
        String key = L404Keys.FEED + userId;
        ops.leftPush(key, contentId);
        ops.trim(key, 0, MAX_TIMELINE_SIZE - 1);
    }

    /**
     * 批量推送：写扩散场景下，发件人写一条、收件人多个。
     */
    public void pushFeeds(String userId, List<String> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) return;
        String key = L404Keys.FEED + userId;
        // contentIds 顺序：旧 → 新；leftPushAll 会反向，最右传入的最终在最左（最新）
        ops.leftPushAll(key, contentIds.toArray(new String[0]));
        ops.trim(key, 0, MAX_TIMELINE_SIZE - 1);
    }

    /**
     * 分页读取：start/end 是 List 下标，0 是最新。
     */
    public List<String> getTimeline(String userId, int start, int end) {
        return ops.range(L404Keys.FEED + userId, start, end);
    }

    public void trimTimeline(String userId, int maxSize) {
        ops.trim(L404Keys.FEED + userId, 0, maxSize - 1);
    }

    public void clear(String userId) {
        template.delete(L404Keys.FEED + userId);
    }
}
