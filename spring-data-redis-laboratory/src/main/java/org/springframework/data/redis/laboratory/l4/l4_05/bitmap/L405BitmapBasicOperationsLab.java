package org.springframework.data.redis.laboratory.l4.l4_05.bitmap;

import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.List;

/**
 * L4-05 Bitmap 命令全家桶（基础实验）。
 * <p>
 * Bitmap 不是独立的数据结构，本质是 Redis String 上的 bit 操作。
 * <ul>
 *   <li>{@code setBit/getBit} 走 ValueOperations</li>
 *   <li>{@code bitCount/bitOp} SDR 的 ValueOperations 没暴露，需要 RedisCallback 拿原生连接</li>
 * </ul>
 * <p>
 * 通用源码链路：
 * <pre>
 * RedisTemplate.opsForValue()
 *   → DefaultValueOperations.setBit/getBit
 *     → RedisTemplate.execute(callback)
 *       → LettuceConnection.stringCommands().setBit/getBit/bitCount/bitOp
 * </pre>
 * <p>
 * <b>offset 必须用 long</b>。offset 决定 String 大小（offset 位 → 至少占 offset/8 字节）。
 * 不要直接把巨大稀疏 userId 当 offset，否则一个用户 setBit 就把 String 撑成 GB 级。
 */
public class L405BitmapBasicOperationsLab {

    private final StringRedisTemplate template;
    private final ValueOperations<String, String> ops;

    public L405BitmapBasicOperationsLab(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    /**
     * SETBIT —— 把 offset 位设为 0/1。
     * SDR API: ValueOperations.setBit(K, long, boolean)
     * 返回值：旧值（true/false）。新写入位之前是 0 → 返回 false。
     */
    public Boolean setBit(String key, long offset, boolean value) {
        return ops.setBit(key, offset, value);
    }

    /**
     * GETBIT —— 读 offset 位。
     * 不存在的 offset 默认是 0（false）。
     */
    public Boolean getBit(String key, long offset) {
        return ops.getBit(key, offset);
    }

    /**
     * BITCOUNT —— 统计为 1 的位数。
     * SDR 的 ValueOperations 没直接暴露，用 RedisCallback 拿 stringCommands。
     * 断点：LettuceStringCommands#bitCount
     */
    public Long bitCount(String key) {
        byte[] rawKey = serializeKey(key);
        Long count = template.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(rawKey));
        return count == null ? 0L : count;
    }

    /**
     * BITCOUNT key start end —— 字节范围统计（注意 start/end 是字节下标）。
     */
    public Long bitCount(String key, long start, long end) {
        byte[] rawKey = serializeKey(key);
        Long count = template.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(rawKey, start, end));
        return count == null ? 0L : count;
    }

    /**
     * BITOP AND —— 多 key 按位与，写到 destKey。
     * 用于"连续活跃天" 分析。
     */
    public Long bitOpAnd(String destKey, String... sourceKeys) {
        return bitOp(RedisStringCommands.BitOperation.AND, destKey, sourceKeys);
    }

    /**
     * BITOP OR —— 多 key 按位或，"任意一天活跃"。
     */
    public Long bitOpOr(String destKey, String... sourceKeys) {
        return bitOp(RedisStringCommands.BitOperation.OR, destKey, sourceKeys);
    }

    /**
     * BITOP XOR —— 多 key 按位异或。
     */
    public Long bitOpXor(String destKey, String... sourceKeys) {
        return bitOp(RedisStringCommands.BitOperation.XOR, destKey, sourceKeys);
    }

    /**
     * BITOP NOT —— 按位取反，只支持一个 source。
     */
    public Long bitOpNot(String destKey, String sourceKey) {
        return bitOp(RedisStringCommands.BitOperation.NOT, destKey, sourceKey);
    }

    private Long bitOp(RedisStringCommands.BitOperation op, String destKey, String... sourceKeys) {
        byte[] rawDest = serializeKey(destKey);
        byte[][] rawSources = new byte[sourceKeys.length][];
        for (int i = 0; i < sourceKeys.length; i++) {
            rawSources[i] = serializeKey(sourceKeys[i]);
        }
        Long size = template.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitOp(op, rawDest, rawSources));
        return size == null ? 0L : size;
    }

    /**
     * BITFIELD GET / SET / INCRBY —— 多位字段操作（演示性能力，本章不深入）。
     */
    public List<Long> bitField(String key, BitFieldSubCommands subCommands) {
        byte[] rawKey = serializeKey(key);
        return template.execute((RedisCallback<List<Long>>) connection ->
                connection.stringCommands().bitField(rawKey, subCommands));
    }

    private byte[] serializeKey(String key) {
        RedisSerializer<String> ser = template.getStringSerializer();
        byte[] raw = ser.serialize(key);
        if (raw == null) {
            throw new IllegalArgumentException("key 不能为空");
        }
        return raw;
    }
}
