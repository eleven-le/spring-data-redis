package org.springframework.data.redis.laboratory.l4.l4_12.toushi.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 偷师 RedisMessageListenerContainer 的三张表设计。
 *
 * <p>SDR 实现细节：用 ByteArrayWrapper 包装 byte[] 作为 ConcurrentHashMap 的 key，
 * 因为 byte[] 自身没有正确 equals/hashCode。我们这里直接用 String，但留下注释帮你
 * 看懂源码里那个奇怪的 ByteArrayWrapper 是干嘛的。
 *
 * <p>三张表的存在意义：
 * - channelMapping：精准订阅"channel→listeners"，分发时 O(1) 查找
 * - patternMapping：模式订阅"pattern→listeners"，分发时仍要遍历所有 pattern 做 match
 * - listenerTopics：listener→topics 反向索引，支持 removeListener 时知道该清哪几张表
 */
public class BusinessListenerRegistry {

    /** channel 字符串 → listeners */
    private final Map<String, Collection<BusinessListener>> channelMapping = new ConcurrentHashMap<>();
    /** pattern 表达式 → listeners */
    private final Map<String, Collection<BusinessListener>> patternMapping = new ConcurrentHashMap<>();
    /** listener → 它注册过的所有 topic，用于反向清理 */
    private final Map<BusinessListener, Collection<BusinessTopic>> listenerTopics = new ConcurrentHashMap<>();

    public void add(BusinessListener listener, Collection<BusinessTopic> topics) {
        listenerTopics
                .computeIfAbsent(listener, k -> new CopyOnWriteArrayList<>())
                .addAll(topics);
        for (BusinessTopic t : topics) {
            Map<String, Collection<BusinessListener>> table =
                    t.getType() == BusinessTopic.Type.CHANNEL ? channelMapping : patternMapping;
            table.computeIfAbsent(t.getExpression(), k -> new CopyOnWriteArrayList<>()).add(listener);
        }
    }

    public void remove(BusinessListener listener) {
        Collection<BusinessTopic> topics = listenerTopics.remove(listener);
        if (topics == null) return;
        for (BusinessTopic t : topics) {
            Map<String, Collection<BusinessListener>> table =
                    t.getType() == BusinessTopic.Type.CHANNEL ? channelMapping : patternMapping;
            Collection<BusinessListener> list = table.get(t.getExpression());
            if (list != null) list.remove(listener);
        }
    }

    public void remove(BusinessListener listener, BusinessTopic topic) {
        Collection<BusinessTopic> tps = listenerTopics.get(listener);
        if (tps != null) tps.remove(topic);
        Map<String, Collection<BusinessListener>> table =
                topic.getType() == BusinessTopic.Type.CHANNEL ? channelMapping : patternMapping;
        Collection<BusinessListener> list = table.get(topic.getExpression());
        if (list != null) list.remove(listener);
    }

    /**
     * 查找一个实际频道的所有匹配 listener。返回的列表带"matchedPattern"信息，
     * 这样后续派发时业务方能区分自己是被精准订阅还是 pattern 命中触发。
     */
    public List<Match> findMatches(String channel) {
        List<Match> result = new ArrayList<>();
        Collection<BusinessListener> direct = channelMapping.get(channel);
        if (direct != null) {
            for (BusinessListener l : direct) result.add(new Match(l, null));
        }
        // pattern 走全量遍历——和 Redis 的 PSUBSCRIBE 服务端逻辑一致
        for (Map.Entry<String, Collection<BusinessListener>> e : patternMapping.entrySet()) {
            String pattern = e.getKey();
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            if (channel.matches(regex)) {
                for (BusinessListener l : e.getValue()) result.add(new Match(l, pattern));
            }
        }
        return result;
    }

    public static class Match {
        public final BusinessListener listener;
        public final String matchedPattern;

        public Match(BusinessListener listener, String matchedPattern) {
            this.listener = listener;
            this.matchedPattern = matchedPattern;
        }
    }
}
