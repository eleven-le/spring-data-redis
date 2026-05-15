package org.springframework.data.redis.laboratory.l4.l4_04.list;

import org.springframework.data.redis.core.BoundListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.time.Duration;

/**
 * 订单履约简单队列：RPUSH 入队，LPOP / BLPOP 出队。
 * <p>
 * 真实业务请注意：
 * 1) 这只是"轻量级"队列。Redis List 没有消费确认 / ack / 死信 / 持久化保证。
 * 2) 一旦 LPOP 后业务执行失败，消息会丢。requeue 是补救，不是终态方案。
 * 3) 高可靠投递请上 RocketMQ / Kafka / RabbitMQ。需要可靠 ack 又非要用 Redis，请用 Stream + consumer group。
 * 4) BLPOP 会独占一条 Redis 连接，连接池要预留余量；timeout 必须设，0 是死等。
 */
public class L404OrderFulfillmentQueueScenario {

    private final BoundListOperations<String, String> queue;

    public L404OrderFulfillmentQueueScenario(StringRedisTemplate template) {
        this.queue = template.boundListOps(L404Keys.ORDER_QUEUE);
    }

    /**
     * 订单入队（履约系统的"上游写入"）。
     */
    public void enqueue(String orderId) {
        queue.rightPush(orderId);
        // 断点: DefaultBoundListOperations.rightPush → DefaultListOperations.rightPush → RedisTemplate.execute
    }

    /**
     * 非阻塞出队，没消息立即返回 null。空轮询模式不要用，会把 CPU 烧光。
     */
    public String dequeue() {
        return queue.leftPop();
    }

    /**
     * 阻塞出队 —— 推荐写法。timeout 一般 1~5 秒，超时返回 null，外层做 while 循环。
     * 坑: 每个消费者线程都要占一条连接，n 个消费者 → n 条连接长期挂着。
     */
    public String blockingDequeue(Duration timeout) {
        return queue.leftPop(timeout);
    }

    /**
     * 消费失败重入队。简陋版，没有重试次数 / 死信，只是演示思路。
     */
    public void requeue(String orderId) {
        // 真实生产：把"重试次数"塞进 message header / 单独的 Hash，超过阈值丢死信
        queue.rightPush(orderId);
    }

    public Long queueSize() {
        return queue.size();
    }

    public void clearQueue() {
        queue.getOperations().delete(queue.getKey());
    }
}
