package org.springframework.data.redis.laboratory.l4.l4_12.demo05;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.laboratory.l4.l4_12.common.JsonCodec;
import org.springframework.data.redis.laboratory.l4.l4_12.common.LogPrinter;

import java.nio.charset.StandardCharsets;

/**
 * 仅承担"轻量通知 + 回查 + 写本地缓存"的职责。
 * 节点宕机 / 重启时它收不到广播——这正是 demo05 要演示的丢消息场景。
 */
public class ConfigChangedListener implements MessageListener {

    private final NodeLocalConfigCache cache;
    private final ConfigStore store;

    public ConfigChangedListener(NodeLocalConfigCache cache, ConfigStore store) {
        this.cache = cache;
        this.store = store;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        ConfigChangedEvent event = JsonCodec.fromJson(body, ConfigChangedEvent.class);
        ConfigStore.Entry latest = store.get(event.getKey());
        if (latest == null) return;
        boolean applied = cache.putIfNewer(latest.key, latest.value, latest.version);
        LogPrinter.print("Listener-" + cache.getNodeName(),
                (applied ? "[apply] " : "[skip] ") +
                        "traceId=" + event.getTraceId() +
                        " key=" + latest.key + " v=" + latest.version);
    }
}
