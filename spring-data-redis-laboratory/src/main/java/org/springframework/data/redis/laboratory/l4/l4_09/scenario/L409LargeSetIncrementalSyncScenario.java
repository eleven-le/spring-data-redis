package org.springframework.data.redis.laboratory.l4.l4_09.scenario;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_09.L409Keys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 场景 5：大 Set 增量同步到下游。
 * <p>
 * <b>业务故事</b>：内容点赞用户集合 {@code l4:09:set:like:item:{itemId}}，
 * 爆款内容点赞用户达到几百万级。运营后台想导出"过去 7 天点赞过 X 的用户"做精准推送，
 * 直接 members() 一拉全部把应用 GC 拖垮。
 * <p>
 * 正解：SSCAN 增量 → 累积一批就推到 Kafka / 写离线表 → 下游做归并。
 * <p>
 * SSCAN 可能重复返回——下游必须幂等（按用户 id 主键去重）。
 */
public class L409LargeSetIncrementalSyncScenario {

    private final StringRedisTemplate template;
    private final SetOperations<String, String> setOps;

    public L409LargeSetIncrementalSyncScenario(StringRedisTemplate template) {
        this.template = template;
        this.setOps = template.opsForSet();
    }

    public String itemKey(String itemId) {
        return L409Keys.SET_DEMO_KEY + ":" + itemId;
    }

    public void prepareLikeUsers(String itemId, int userCount) {
        String key = itemKey(itemId);
        List<String> buffer = new ArrayList<>(1000);
        for (int i = 0; i < userCount; i++) {
            buffer.add("u" + i);
            if (buffer.size() >= 1000) {
                setOps.add(key, buffer.toArray(new String[0]));
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            setOps.add(key, buffer.toArray(new String[0]));
        }
    }

    /**
     * SSCAN 增量同步。返回去重后实际推送下游的 user 数。
     */
    public long syncLikeUsersBySScan(String itemId, int scanCount, int batchSize) {
        String key = itemKey(itemId);
        ScanOptions options = ScanOptions.scanOptions().count(scanCount).build();
        long synced = 0;
        Set<String> seen = new HashSet<>();
        List<String> buffer = new ArrayList<>(batchSize);
        try (Cursor<String> cursor = setOps.scan(key, options)) {
            while (cursor.hasNext()) {
                String user = cursor.next();
                if (!seen.add(user)) continue;
                buffer.add(user);
                if (buffer.size() >= batchSize) {
                    sendDownstream(buffer);
                    synced += buffer.size();
                    buffer.clear();
                }
            }
            if (!buffer.isEmpty()) {
                sendDownstream(buffer);
                synced += buffer.size();
            }
        }
        return synced;
    }

    /**
     * 反例：members() 拉全部，请勿在生产对热点 Set 用。
     */
    @Deprecated
    public Set<String> syncLikeUsersByMembersDangerExample(String itemId) {
        return setOps.members(itemKey(itemId));
    }

    public void cleanup(String itemId) {
        template.delete(itemKey(itemId));
    }

    private void sendDownstream(List<String> users) {
        // 模拟：业务里换成 Kafka / 离线表 / 推送系统
    }
}
