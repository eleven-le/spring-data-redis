package org.springframework.data.redis.laboratory.l4.l4_12.common;

import java.util.UUID;

/**
 * traceId 生成器。生产环境通常由 SkyWalking / SLS / 自研链路 SDK 注入；
 * 这里用 UUID 截短模拟，目的只为让 Pub/Sub 跨节点的日志能对账。
 */
public final class TraceIds {
    public static String next() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private TraceIds() {
    }
}
