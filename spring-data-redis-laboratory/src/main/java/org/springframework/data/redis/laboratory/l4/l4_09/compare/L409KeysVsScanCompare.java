package org.springframework.data.redis.laboratory.l4.l4_09.compare;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对比实验：KEYS vs SCAN。
 * <p>
 * <b>KEYS 是反例</b>，本类仅用于学习对照与生产事故复盘演练，<b>禁止</b>在生产业务代码使用。
 */
public class L409KeysVsScanCompare {

    private final StringRedisTemplate template;

    public L409KeysVsScanCompare(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * <b>反例</b>：KEYS pattern。
     * 时间复杂度 O(N)，N 是整个 Redis 实例 key 数量；执行期间阻塞主线程，所有其它请求排队。
     */
    @Deprecated
    public Set<String> keysDangerExample(String pattern) {
        return template.keys(pattern);
    }

    /**
     * 推荐：SCAN pattern。
     * 单轮 O(count)，分多轮增量进行；其它请求可以在轮次之间被处理；不阻塞业务接口。
     */
    public List<String> scanSafeExample(String pattern, int count, int maxKeys) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(count).build();
        List<String> result = new ArrayList<>();
        try (Cursor<String> cursor = template.scan(options)) {
            while (cursor.hasNext() && result.size() < maxKeys) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /**
     * 写在代码里方便断点对照——讲清 KEYS 为什么不能上生产。
     */
    public String explainWhyKeysDangerous() {
        return ""
                + "1. Redis 单线程：KEYS 期间所有命令排队；\n"
                + "2. 时间复杂度 O(N)：N 是整个 keyspace 大小，不只是匹配 key 数量；\n"
                + "3. 大量 key 一次返回：网络流量与客户端内存压力同时拉满；\n"
                + "4. SCAN 把单次大开销摊薄成多轮小开销，主线程在轮次间能处理其它命令；\n"
                + "5. 测试环境 1ms 跑完不代表生产 1ms——生产 keyspace 大几个数量级。";
    }
}
