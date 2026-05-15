package org.springframework.data.redis.laboratory.l4.l4_06.consumer;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_06.L406Keys;
import org.springframework.data.redis.laboratory.l4.l4_06.dlq.DeadLetterStreamPublisher;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.BizErrorType;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.ConsumeResult;
import org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEvent;
import org.springframework.data.redis.laboratory.l4.l4_06.idempotent.StreamConsumeIdempotentService;

import java.math.BigDecimal;

/**
 * Notion 章节：L4-06 Stream 消息队列 / 02 真实业务场景 → 风控检查
 *
 * 真实场景：
 *  支付成功事件触发风控检查：高额订单、异常 deviceId、黑名单 IP。
 *  风控本身不阻塞主链路，但是检查"事后命中"必须落库：
 *   - 命中黑名单 → DEAD_LETTER（业务方主动判定无法继续 normal flow，转人工）；
 *   - 风控引擎不可用 → RETRYABLE；
 *   - 数据缺失 deviceId/IP → NON_RETRYABLE。
 */
public class RiskCheckStreamConsumer extends AbstractOrderEventStreamListener {

    private static final BigDecimal HIGH_AMOUNT_THRESHOLD = new BigDecimal("100000");

    public RiskCheckStreamConsumer(StringRedisTemplate redis,
                                   StreamConsumeIdempotentService idempotent,
                                   DeadLetterStreamPublisher dlq,
                                   String consumerName) {
        super(redis, idempotent, dlq, L406Keys.GROUP_RISK, consumerName);
    }

    @Override
    protected ConsumeResult handleEvent(OrderEvent event, MapRecord<String, String, String> record) {
        if (event.getEventType() != org.springframework.data.redis.laboratory.l4.l4_06.domain.OrderEventType.ORDER_PAID) {
            return ConsumeResult.SUCCESS;
        }
        if (event.getPayload() == null
                || event.getPayload().getDeviceId() == null
                || event.getPayload().getClientIp() == null) {
            throw new IllegalArgumentException("风控参数缺失 orderId=" + event.getOrderId());
        }

        BigDecimal payAmount = event.getPayload().getPayAmount();
        if (payAmount != null && payAmount.compareTo(HIGH_AMOUNT_THRESHOLD) >= 0) {
            log("风控命中：高额订单 orderId={} amount={}", event.getOrderId(), payAmount);
            return ConsumeResult.DEAD_LETTER;
        }

        log("风控通过 orderId={} ip={} device={}",
                event.getOrderId(),
                event.getPayload().getClientIp(),
                event.getPayload().getDeviceId());
        return ConsumeResult.SUCCESS;
    }

    /** 演示子类自定义异常分型：风控引擎相关异常默认是可重试。 */
    @Override
    protected BizErrorType classify(Exception ex) {
        // 父类已处理 IllegalArgumentException → NON_RETRYABLE
        return super.classify(ex);
    }
}
