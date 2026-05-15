package org.springframework.data.redis.laboratory.l4.l4_09.compare;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对比：HSCAN/SSCAN vs entries/members。
 * <p>
 * 大容器场景必须用 *SCAN——entries/members 是 O(N) 一次拉全量，
 * 客户端内存暴涨、Redis 主线程序列化时间长。
 */
public class L409ScanVsMembersEntriesCompare {

    private final StringRedisTemplate template;
    private final HashOperations<String, String, String> hashOps;
    private final SetOperations<String, String> setOps;

    public L409ScanVsMembersEntriesCompare(StringRedisTemplate template) {
        this.template = template;
        this.hashOps = template.opsForHash();
        this.setOps = template.opsForSet();
    }

    /**
     * 推荐：HSCAN 替代 entries。
     */
    public List<Map.Entry<String, String>> hscanInsteadOfEntries(String key, int count, int max) {
        ScanOptions options = ScanOptions.scanOptions().count(count).build();
        List<Map.Entry<String, String>> result = new ArrayList<>();
        try (Cursor<Map.Entry<String, String>> cursor = hashOps.scan(key, options)) {
            while (cursor.hasNext() && result.size() < max) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /**
     * 推荐：SSCAN 替代 members。
     */
    public List<String> sscanInsteadOfMembers(String key, int count, int max) {
        ScanOptions options = ScanOptions.scanOptions().count(count).build();
        List<String> result = new ArrayList<>();
        try (Cursor<String> cursor = setOps.scan(key, options)) {
            while (cursor.hasNext() && result.size() < max) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /**
     * 反例：entries() / members() 一次拉全量，仅供断点对照。
     */
    @Deprecated
    public Map<String, String> dangerEntriesExample(String key) {
        return hashOps.entries(key);
    }

    @Deprecated
    public Set<String> dangerMembersExample(String key) {
        return setOps.members(key);
    }
}
