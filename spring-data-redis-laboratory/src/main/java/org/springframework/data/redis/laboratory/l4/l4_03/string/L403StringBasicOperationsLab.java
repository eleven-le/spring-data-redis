package org.springframework.data.redis.laboratory.l4.l4_03.string;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * String 命令全家桶实验：把 ValueOperations 上每一个常用方法跑一遍，
 * 每一个动作都对应一行真实 Redis 命令 + 一条建议断点。
 * <p>
 * 学习方式：
 * 1) 用 Spring 容器启动（见 debug.L403StringDebugMain），
 * 在每个方法的 ops.* 调用上打断点；
 * 2) Step Into → DefaultValueOperations.xxx → RedisTemplate.execute → RedisCallback.doInRedis →
 * RedisConnectionUtils.doGetConnection → LettuceConnection.stringCommands → 真实 Lettuce 命令。
 */
public class L403StringBasicOperationsLab {

    private final StringRedisTemplate template;
    private final ValueOperations<String, String> ops;

    public L403StringBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    /**
     * SET key value —— DefaultValueOperations#set(K,V)
     */
    public void demoSet() {
        String key = L403Keys.STRING_LAB + "set";
        ops.set(key, "hello-l4-03");
        System.out.println("[set] " + key + " = " + ops.get(key));
        // 断点: DefaultValueOperations.set 第一行 → execute → connection.stringCommands().set
    }

    /**
     * GET key —— DefaultValueOperations#get(Object)
     */
    public void demoGet() {
        String key = L403Keys.STRING_LAB + "get";
        ops.set(key, "hi");
        String v = ops.get(key);
        System.out.println("[get] " + key + " = " + v);
    }

    /**
     * SET key value EX seconds —— 一次写入 + TTL，原子
     */
    public void demoSetWithTimeout() {
        String key = L403Keys.STRING_LAB + "setTtl";
        ops.set(key, "expire-in-30s", Duration.ofSeconds(30));
        Long ttl = template.getExpire(key);
        System.out.println("[set TTL] " + key + " ttl=" + ttl + "s");
        // 坑: 不要写成 set + expire 两步，宕机就成了永久 key
    }

    /**
     * SETNX —— ops.setIfAbsent，分布式锁/幂等的基石
     */
    public void demoSetIfAbsent() {
        String key = L403Keys.STRING_LAB + "setNx";
        Boolean first = ops.setIfAbsent(key, "first", Duration.ofSeconds(60));
        Boolean second = ops.setIfAbsent(key, "second", Duration.ofSeconds(60));
        System.out.println("[setIfAbsent] first=" + first + " second=" + second
                + " value=" + ops.get(key));
        // 注意: setIfAbsent(K,V) 重载没带 TTL —— 业务里几乎永远要带 Duration 重载
    }

    /**
     * SET XX —— 只在 key 存在时覆盖
     */
    public void demoSetIfPresent() {
        String key = L403Keys.STRING_LAB + "setXx";
        template.delete(key);
        Boolean a = ops.setIfPresent(key, "a"); // false: 不存在不写
        ops.set(key, "init");
        Boolean b = ops.setIfPresent(key, "b"); // true: 存在则覆盖
        System.out.println("[setIfPresent] firstNotExist=" + a + " thenOverwrite=" + b
                + " value=" + ops.get(key));
    }

    /**
     * INCR / INCRBY —— 原子计数
     */
    public void demoIncrement() {
        String key = L403Keys.STRING_LAB + "incr";
        template.delete(key);
        Long a = ops.increment(key);          // 1
        Long b = ops.increment(key, 10L);     // 11
        Double c = ops.increment(key, 0.5);   // 11.5 (走 INCRBYFLOAT，注意类型变化)
        System.out.println("[increment] +1=" + a + " +10=" + b + " +0.5=" + c);
        // 关键: 原子性来自 Redis 单线程命令执行，Java 层只是透传
    }

    /**
     * DECR / DECRBY —— 原子自减
     */
    public void demoDecrement() {
        String key = L403Keys.STRING_LAB + "decr";
        ops.set(key, "100");
        Long a = ops.decrement(key);     // 99
        Long b = ops.decrement(key, 9);  // 90
        System.out.println("[decrement] -1=" + a + " -9=" + b);
    }

    /**
     * APPEND —— 末尾追加
     */
    public void demoAppend() {
        String key = L403Keys.STRING_LAB + "append";
        template.delete(key);
        ops.append(key, "Hello, ");
        Integer len = ops.append(key, "World!");
        System.out.println("[append] len=" + len + " value=" + ops.get(key));
    }

    /**
     * STRLEN —— 字节长度（取决于 serializer 后的字节）
     */
    public void demoSize() {
        String key = L403Keys.STRING_LAB + "size";
        ops.set(key, "abc中文");
        Long size = ops.size(key);
        System.out.println("[size] bytes=" + size);
        // 坑: 这里返回的是序列化后的字节数，不是 Java String.length()
    }

    /**
     * MGET —— 一次拿多个 key
     */
    public void demoMultiGet() {
        ops.set(L403Keys.STRING_LAB + "m1", "1");
        ops.set(L403Keys.STRING_LAB + "m2", "2");
        ops.set(L403Keys.STRING_LAB + "m3", "3");
        List<String> values = ops.multiGet(Arrays.asList(
                L403Keys.STRING_LAB + "m1",
                L403Keys.STRING_LAB + "m2",
                L403Keys.STRING_LAB + "missing",
                L403Keys.STRING_LAB + "m3"));
        System.out.println("[multiGet] " + values); // missing 位置是 null，不是抛异常
    }

    /**
     * GETSET —— 取旧值并写入新值（原子）
     */
    public void demoGetAndSet() {
        String key = L403Keys.STRING_LAB + "getSet";
        ops.set(key, "old");
        String old = ops.getAndSet(key, "new");
        System.out.println("[getAndSet] old=" + old + " current=" + ops.get(key));
        // 典型用法: 重置计数器 + 同时拿到上一周期的累计值
    }

    /**
     * 一次跑完，方便在 main 中观察执行流。
     */
    public void runAll() {
        demoSet();
        demoGet();
        demoSetWithTimeout();
        demoSetIfAbsent();
        demoSetIfPresent();
        demoIncrement();
        demoDecrement();
        demoAppend();
        demoSize();
        demoMultiGet();
        demoGetAndSet();
    }
}
