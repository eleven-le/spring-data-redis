package org.springframework.data.redis.laboratory.l4.l4_10.result;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * 延迟队列抢占返回值。
 * <p>
 * 真实场景:茶饮订单"15 分钟未支付自动取消"消费者抢任务时,
 * 不仅要拿 taskId,还要拿 payload(订单号、用户、金额),拿到 attempts 决定告警,
 * 拿到 visibilityDeadlineMs 决定何时回滚到 ready 队列。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DelayQueueClaimResult {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Task {
        public String taskId;
        public Long attempts;
        public String payload;
        public Long scheduledAtMs;
        public Long visibilityDeadlineMs;

        @Override public String toString() {
            return String.format("Task{id=%s, attempts=%s, payload=%s, vis=%s}",
                    taskId, attempts, payload, visibilityDeadlineMs);
        }
    }

    public int code;
    public String msg;
    public Long claimedCount;
    public List<Task> claimed = Collections.emptyList();
    public List<String> deadLetter = Collections.emptyList();
    public Long ts;

    @Override public String toString() {
        return String.format("DelayQueueClaimResult{code=%d, claimedCount=%s, claimed=%s, deadLetter=%s}",
                code, claimedCount, claimed, deadLetter);
    }
}
