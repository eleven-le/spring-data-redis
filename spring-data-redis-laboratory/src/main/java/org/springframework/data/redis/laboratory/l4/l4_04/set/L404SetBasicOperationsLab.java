package org.springframework.data.redis.laboratory.l4.l4_04.set;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Redis Set 命令全家桶实验。
 * <p>
 * 断点路径：
 * DefaultSetOperations.xxx → RedisTemplate.execute(callback) →
 * RedisCallback.doInRedis → connection.setCommands().sAdd/sIsMember/sMembers/...
 */
public class L404SetBasicOperationsLab {

    private final StringRedisTemplate template;
    private final SetOperations<String, String> ops;

    public L404SetBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForSet();
    }

    /**
     * SADD —— 加入元素，重复元素忽略。返回新增数量。
     */
    public void demoAdd() {
        String key = L404Keys.SET_LAB + "add";
        template.delete(key);
        Long added = ops.add(key, "a", "b", "c", "a"); // 4 个里有 1 个重复
        System.out.println("[SADD]   added=" + added + " members=" + ops.members(key));
    }

    /**
     * SREM —— 删元素。
     */
    public void demoRemove() {
        String key = L404Keys.SET_LAB + "rem";
        template.delete(key);
        ops.add(key, "x", "y", "z");
        Long removed = ops.remove(key, "y");
        System.out.println("[SREM]   removed=" + removed + " left=" + ops.members(key));
    }

    /**
     * SISMEMBER —— 判存在。Set 最常用动作之一。
     */
    public void demoIsMember() {
        String key = L404Keys.SET_LAB + "ismember";
        template.delete(key);
        ops.add(key, "u-1", "u-2");
        System.out.println("[SISMEMBER] u-1=" + ops.isMember(key, "u-1")
                + " u-3=" + ops.isMember(key, "u-3"));
        // 坑: 循环逐个 isMember N 次 = N 次 RTT。批量请用 isMember(key, Object...) 或 6.2+ 的 SMISMEMBER。
    }

    /**
     * SMEMBERS —— 全量。坑：大 Set 一次拉全可以拖垮服务，生产应 SSCAN。
     */
    public void demoMembers() {
        String key = L404Keys.SET_LAB + "members";
        template.delete(key);
        ops.add(key, "1", "2", "3");
        System.out.println("[SMEMBERS] " + ops.members(key));
    }

    /**
     * SCARD —— 元素数。O(1)。
     */
    public void demoSize() {
        String key = L404Keys.SET_LAB + "size";
        template.delete(key);
        ops.add(key, "a", "b", "c");
        System.out.println("[SCARD]  size=" + ops.size(key));
    }

    /**
     * SPOP —— 随机弹出（破坏性）。抽奖、随机分桶常用。
     */
    public void demoPop() {
        String key = L404Keys.SET_LAB + "pop";
        template.delete(key);
        ops.add(key, "u-1", "u-2", "u-3", "u-4", "u-5");
        System.out.println("[SPOP]   one=" + ops.pop(key) + " left=" + ops.members(key));
    }

    /**
     * SRANDMEMBER —— 随机看一眼，不删除。
     */
    public void demoRandomMember() {
        String key = L404Keys.SET_LAB + "rand";
        template.delete(key);
        ops.add(key, "u-1", "u-2", "u-3", "u-4", "u-5");
        System.out.println("[SRANDMEMBER] one=" + ops.randomMember(key));
    }

    /**
     * SRANDMEMBER count —— 随机不重复 N 个。
     */
    public void demoDistinctRandomMembers() {
        String key = L404Keys.SET_LAB + "drand";
        template.delete(key);
        ops.add(key, "u-1", "u-2", "u-3", "u-4", "u-5");
        System.out.println("[SRANDMEMBER 3] " + ops.distinctRandomMembers(key, 3));
    }

    /**
     * SUNION —— 并集。营销场景"任一人群命中"。
     */
    public void demoUnion() {
        String a = L404Keys.SET_LAB + "u:a";
        String b = L404Keys.SET_LAB + "u:b";
        template.delete(Arrays.asList(a, b));
        ops.add(a, "1", "2", "3");
        ops.add(b, "3", "4", "5");
        System.out.println("[SUNION] " + ops.union(a, b));
    }

    /**
     * SINTER —— 交集。"共同关注"、"共同兴趣"。坑：大 Set 计算贵。
     */
    public void demoIntersect() {
        String a = L404Keys.SET_LAB + "i:a";
        String b = L404Keys.SET_LAB + "i:b";
        template.delete(Arrays.asList(a, b));
        ops.add(a, "1", "2", "3");
        ops.add(b, "3", "4", "5");
        System.out.println("[SINTER] " + ops.intersect(a, b));
    }

    /**
     * SDIFF —— 差集。"在 a 不在 b"。"白名单 - 黑名单"。
     */
    public void demoDifference() {
        String a = L404Keys.SET_LAB + "d:a";
        String b = L404Keys.SET_LAB + "d:b";
        template.delete(Arrays.asList(a, b));
        ops.add(a, "1", "2", "3");
        ops.add(b, "3", "4", "5");
        System.out.println("[SDIFF]  a-b=" + ops.difference(a, b));
    }

    /**
     * SSCAN —— 游标遍历，生产环境取代 SMEMBERS 的正解。
     */
    public void demoScan() {
        String key = L404Keys.SET_LAB + "scan";
        template.delete(key);
        for (int i = 0; i < 50; i++) ops.add(key, "u-" + i);
        try (Cursor<String> cursor = ops.scan(key, ScanOptions.scanOptions().match("u-*").count(10).build())) {
            int n = 0;
            while (cursor.hasNext() && n < 5) {
                System.out.println("[SSCAN]  -> " + cursor.next());
                n++;
            }
        }
        // 断点: DefaultSetOperations.scan → execute(callback) → connection.sScan
        //       注意 try-with-resources：Cursor 必须关闭，否则会泄露 native cursor。
    }

    public void runAll() {
        demoAdd();
        demoRemove();
        demoIsMember();
        demoMembers();
        demoSize();
        demoPop();
        demoRandomMember();
        demoDistinctRandomMembers();
        demoUnion();
        demoIntersect();
        demoDifference();
        demoScan();
    }
}
