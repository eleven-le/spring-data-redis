package org.springframework.data.redis.laboratory.l4.l4_03.hash;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hash 命令全家桶实验。每个方法对应一个 Redis Hash 命令，
 * 演示 DefaultHashOperations 如何把 Java 调用翻译成 RedisConnection.hashCommands().*。
 * <p>
 * 推荐断点：
 * <ul>
 *   <li>RedisTemplate#opsForHash —— 看子门面如何按需创建</li>
 *   <li>DefaultHashOperations#put —— 进入 execute(callback)</li>
 *   <li>RedisConnection#hashCommands —— Lettuce/Jedis 命令分流</li>
 * </ul>
 */
public class L403HashBasicOperationsLab {

    private final RedisTemplate<String, Object> template;
    private final HashOperations<String, String, Object> ops;

    public L403HashBasicOperationsLab(RedisTemplate<String, Object> template) {
        this.template = template;
        this.ops = template.opsForHash();
    }

    private String key(String tag) {
        return L403Keys.HASH_LAB + tag;
    }

    /**
     * HSET key field value
     */
    public void demoPut() {
        String k = key("put");
        ops.put(k, "nickname", "奶茶狂魔");
        System.out.println("[put] " + k + " nickname=" + ops.get(k, "nickname"));
    }

    /**
     * HSETNX key field value —— field 不存在才写
     */
    public void demoPutIfAbsent() {
        String k = key("putNx");
        template.delete(k);
        Boolean a = ops.putIfAbsent(k, "level", "GOLD");
        Boolean b = ops.putIfAbsent(k, "level", "PLATINUM"); // 已存在不会覆盖
        System.out.println("[putIfAbsent] first=" + a + " again=" + b
                + " final=" + ops.get(k, "level"));
    }

    /**
     * HMSET key f1 v1 f2 v2 —— 一次写入多个 field
     */
    public void demoPutAll() {
        String k = key("putAll");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nickname", "张三");
        m.put("level", "GOLD");
        m.put("points", 1234);
        ops.putAll(k, m);
        System.out.println("[putAll] entries=" + ops.entries(k));
    }

    /**
     * HGET key field
     */
    public void demoGet() {
        String k = key("get");
        ops.put(k, "phone", "138****1234");
        System.out.println("[get] phone=" + ops.get(k, "phone"));
    }

    /**
     * HMGET key f1 f2 f3 —— 部分字段读取，避免拉整张大 Hash
     */
    public void demoMultiGet() {
        String k = key("mget");
        ops.putAll(k, Map.of("a", "1", "b", "2", "c", "3"));
        List<Object> values = ops.multiGet(k, Arrays.asList("a", "missing", "c"));
        System.out.println("[multiGet] " + values); // [1, null, 3]
    }

    /**
     * HGETALL —— 读出整张表，警惕大 Hash
     */
    public void demoEntries() {
        String k = key("entries");
        ops.putAll(k, Map.of("x", "1", "y", "2"));
        Map<String, Object> all = ops.entries(k);
        System.out.println("[entries] " + all);
        // 坑: 字段过多时 HGETALL 会一次性把 N 个 field/value 反序列化，拖垮接口
    }

    /**
     * HEXISTS key field
     */
    public void demoHasKey() {
        String k = key("has");
        ops.put(k, "ok", "1");
        Boolean exists = ops.hasKey(k, "ok");
        Boolean miss = ops.hasKey(k, "no");
        System.out.println("[hasKey] ok=" + exists + " miss=" + miss);
    }

    /**
     * HDEL key f1 f2 —— 删除部分字段
     */
    public void demoDelete() {
        String k = key("del");
        ops.putAll(k, Map.of("f1", "1", "f2", "2", "f3", "3"));
        Long removed = ops.delete(k, "f1", "f2");
        System.out.println("[delete] removed=" + removed + " remain=" + ops.entries(k));
    }

    /**
     * HINCRBY key field increment —— 字段级原子自增
     */
    public void demoIncrement() {
        String k = key("incr");
        template.delete(k);
        Long a = ops.increment(k, "points", 1);
        Long b = ops.increment(k, "points", 50);
        Double c = ops.increment(k, "rate", 0.5);
        System.out.println("[increment] points=" + a + "->" + b + " rate=" + c);
    }

    /**
     * HKEYS —— 列出所有字段名
     */
    public void demoKeys() {
        String k = key("keys");
        ops.putAll(k, Map.of("a", "1", "b", "2"));
        Set<String> fields = ops.keys(k);
        System.out.println("[keys] " + fields);
    }

    /**
     * HVALS —— 列出所有值
     */
    public void demoValues() {
        String k = key("vals");
        ops.putAll(k, Map.of("a", "1", "b", "2"));
        List<Object> vals = ops.values(k);
        System.out.println("[values] " + vals);
    }

    /**
     * HLEN —— field 数量
     */
    public void demoSize() {
        String k = key("size");
        ops.putAll(k, Map.of("a", "1", "b", "2", "c", "3"));
        Long size = ops.size(k);
        System.out.println("[size] " + size);
    }

    /**
     * HSCAN —— 游标式遍历，避免一次性 HGETALL 大 Hash。
     * 注意：scan 不是强一致快照，遍历期间被修改的 field 行为未定义；
     * 通常用于"运维侧"批量盘点，业务热路径不要依赖它。
     */
    public void demoScan() {
        String k = key("scan");
        Map<String, Object> bulk = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            bulk.put("f" + i, "v" + i);
        }
        ops.putAll(k, bulk);
        ScanOptions opts = ScanOptions.scanOptions().match("f*").count(10).build();
        try (Cursor<Map.Entry<String, Object>> cursor = ops.scan(k, opts)) {
            int n = 0;
            while (cursor.hasNext()) {
                Map.Entry<String, Object> e = cursor.next();
                if (n++ < 3) {
                    System.out.println("[scan] " + e.getKey() + "=" + e.getValue());
                }
            }
            System.out.println("[scan] total visited=" + n);
        }
    }

    public void runAll() {
        demoPut();
        demoPutIfAbsent();
        demoPutAll();
        demoGet();
        demoMultiGet();
        demoEntries();
        demoHasKey();
        demoDelete();
        demoIncrement();
        demoKeys();
        demoValues();
        demoSize();
        demoScan();
    }
}
