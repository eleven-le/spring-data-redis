package org.springframework.data.redis.laboratory.l4.l4_04.list;

import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Redis List 命令全家桶实验。每个 demoXxx 对应一条 Redis 命令 + 一条建议断点。
 * <p>
 * 学习方式：
 * 1) 用 Spring 容器启动（见 debug.L404ListDebugMain）；
 * 2) 在每个 ops.* 调用上打断点，Step Into：
 * DefaultListOperations.xxx → RedisTemplate.execute(callback) →
 * RedisCallback.doInRedis → RedisConnectionUtils.doGetConnection →
 * LettuceConnection.listCommands → 真实 Lettuce 命令；
 * 3) 同时打开 redis-cli MONITOR，观察 LPUSH/RPUSH/BRPOP 真实下发。
 */
public class L404ListBasicOperationsLab {

    private final StringRedisTemplate template;
    private final ListOperations<String, String> ops;

    public L404ListBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForList();
    }

    /**
     * LPUSH —— 头插。返回插入后列表长度。坑：长度不是新元素索引。
     */
    public void demoLeftPush() {
        String key = L404Keys.LIST_LAB + "lpush";
        template.delete(key);
        Long size1 = ops.leftPush(key, "A");
        Long size2 = ops.leftPushAll(key, "B", "C", "D"); // 等价于多次 LPUSH
        System.out.println("[LPUSH]  size1=" + size1 + " size2=" + size2
                + " range=" + ops.range(key, 0, -1));
        // 断点: DefaultListOperations.leftPush → execute(connection -> connection.listCommands().lPush(...))
    }

    /**
     * RPUSH —— 尾插。FIFO 队列入口。
     */
    public void demoRightPush() {
        String key = L404Keys.LIST_LAB + "rpush";
        template.delete(key);
        ops.rightPush(key, "task-1");
        ops.rightPushAll(key, "task-2", "task-3");
        System.out.println("[RPUSH]  range=" + ops.range(key, 0, -1));
    }

    /**
     * LPOP —— 头部弹出。RPUSH+LPOP 组成 FIFO 队列。
     */
    public void demoLeftPop() {
        String key = L404Keys.LIST_LAB + "lpop";
        template.delete(key);
        ops.rightPushAll(key, "x", "y", "z");
        System.out.println("[LPOP]   first=" + ops.leftPop(key)
                + " remaining=" + ops.range(key, 0, -1));
    }

    /**
     * RPOP —— 尾部弹出。LPUSH+RPOP 也能组队列；LPUSH+LPOP 则是栈。
     */
    public void demoRightPop() {
        String key = L404Keys.LIST_LAB + "rpop";
        template.delete(key);
        ops.rightPushAll(key, "x", "y", "z");
        System.out.println("[RPOP]   last=" + ops.rightPop(key)
                + " remaining=" + ops.range(key, 0, -1));
    }

    /**
     * LRANGE start stop —— 取范围。-1 表示最后一个。坑：大范围会拖慢服务。
     */
    public void demoRange() {
        String key = L404Keys.LIST_LAB + "range";
        template.delete(key);
        ops.rightPushAll(key, "a", "b", "c", "d", "e");
        System.out.println("[LRANGE 0 -1]   " + ops.range(key, 0, -1));
        System.out.println("[LRANGE 0 2 ]   " + ops.range(key, 0, 2));   // 取最早 3 条
        System.out.println("[LRANGE -3 -1] " + ops.range(key, -3, -1)); // 取最近 3 条
    }

    /**
     * LTRIM start stop —— 只保留范围内元素。"最近 N 条" 必备。
     */
    public void demoTrim() {
        String key = L404Keys.LIST_LAB + "trim";
        template.delete(key);
        for (int i = 1; i <= 20; i++) ops.leftPush(key, "item-" + i); // 头插，最新在前
        ops.trim(key, 0, 9); // 只保留最新 10 条
        System.out.println("[LTRIM]  size=" + ops.size(key) + " range=" + ops.range(key, 0, -1));
    }

    /**
     * LINDEX —— 按下标读。0 是最早 LPUSH 进去的反向首元素。坑：不能像 ArrayList 一样高效随机访问。
     */
    public void demoIndex() {
        String key = L404Keys.LIST_LAB + "index";
        template.delete(key);
        ops.rightPushAll(key, "head", "mid", "tail");
        System.out.println("[LINDEX] 0=" + ops.index(key, 0)
                + " 1=" + ops.index(key, 1)
                + " -1=" + ops.index(key, -1));
    }

    /**
     * LLEN —— 列表长度。O(1)。
     */
    public void demoSize() {
        String key = L404Keys.LIST_LAB + "size";
        template.delete(key);
        ops.rightPushAll(key, Arrays.asList("a", "b", "c"));
        System.out.println("[LLEN]   size=" + ops.size(key));
    }

    /**
     * LREM count value —— 按值删除。count>0 从头删，count<0 从尾删，count=0 全删。
     */
    public void demoRemove() {
        String key = L404Keys.LIST_LAB + "remove";
        template.delete(key);
        ops.rightPushAll(key, "x", "y", "x", "z", "x");
        Long removed = ops.remove(key, 0, "x"); // 删除全部 "x"
        System.out.println("[LREM]   removed=" + removed + " remain=" + ops.range(key, 0, -1));
    }

    /**
     * LSET index value —— 按下标改写。坑：下标越界抛异常。
     */
    public void demoSet() {
        String key = L404Keys.LIST_LAB + "set";
        template.delete(key);
        ops.rightPushAll(key, "a", "b", "c");
        ops.set(key, 1, "B"); // 把第 1 位改成 B
        System.out.println("[LSET]   range=" + ops.range(key, 0, -1));
    }

    /**
     * RPOPLPUSH src dst —— 原子尾出 + 头入。可做"可靠队列"备份槽。
     */
    public void demoRightPopAndLeftPush() {
        String src = L404Keys.LIST_LAB + "rpoplpush:src";
        String dst = L404Keys.LIST_LAB + "rpoplpush:dst";
        template.delete(src);
        template.delete(dst);
        ops.rightPushAll(src, "msg-1", "msg-2", "msg-3");
        String moved = ops.rightPopAndLeftPush(src, dst);
        System.out.println("[RPOPLPUSH] moved=" + moved
                + " src=" + ops.range(src, 0, -1)
                + " dst=" + ops.range(dst, 0, -1));
        // 偷师: 这是"先放到处理中槽 → 处理完再删"的可靠消费模型雏形。
    }

    /**
     * BLPOP —— 阻塞头部弹出。坑：阻塞会占用一条连接；timeout 必须设。
     */
    public void demoBlockingLeftPop() {
        String key = L404Keys.LIST_LAB + "blpop";
        template.delete(key);
        ops.rightPush(key, "ready");
        String v = ops.leftPop(key, Duration.ofSeconds(1)); // 有数据立即返回
        System.out.println("[BLPOP]  hot=" + v + " emptyTry=" + ops.leftPop(key, Duration.ofMillis(300)));
    }

    /**
     * BRPOP —— 阻塞尾部弹出。
     */
    public void demoBlockingRightPop() {
        String key = L404Keys.LIST_LAB + "brpop";
        template.delete(key);
        ops.rightPush(key, "ready");
        String v = ops.rightPop(key, Duration.ofSeconds(1));
        System.out.println("[BRPOP]  hot=" + v);
    }

    public void runAll() {
        demoLeftPush();
        demoRightPush();
        demoLeftPop();
        demoRightPop();
        demoRange();
        demoTrim();
        demoIndex();
        demoSize();
        demoRemove();
        demoSet();
        demoRightPopAndLeftPush();
        demoBlockingLeftPop();
        demoBlockingRightPop();
    }
}
