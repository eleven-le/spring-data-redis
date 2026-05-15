package org.springframework.data.redis.laboratory.l4.l4_08.scenario;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_08.L408Keys;
import org.springframework.data.redis.laboratory.l4.l4_08.transaction.L408Transactions;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 用户任务完成 + 积分 + 排行榜更新（事务版本）。
 * <p>
 * 业务诉求：仅希望"打包写"，不需要"读后判断"——所以这里 <b>不用 WATCH</b>。
 * 但仍然要意识到：
 * <ul>
 *   <li>事务里某条命令运行错误不会回滚其他命令；</li>
 *   <li>必须监控 EXEC 错误率；</li>
 *   <li>大批量推荐用 Pipeline，事务命令尽量在几十条内。</li>
 * </ul>
 */
public class L408TaskRewardTransactionScenario {

    private final StringRedisTemplate template;

    public L408TaskRewardTransactionScenario(StringRedisTemplate template) {
        this.template = template;
    }

    /**
     * 完成任务：原子打包写入 task 状态、积分增量、排行榜增量。
     */
    public List<Object> completeTask(String userId,
                                     String taskId,
                                     long rewardPoints,
                                     String date) {
        String taskKey = L408Keys.taskStatus(userId, taskId);
        String pointsKey = L408Keys.pointsUser(userId);
        String rankKey = L408Keys.taskRank(date);

        return L408Transactions.runTxString(template, ops -> {
            ops.multi();
            ops.opsForValue().set(taskKey, "DONE");
            ops.expire(taskKey, Duration.ofDays(30));
            ops.opsForValue().increment(pointsKey, rewardPoints);
            ops.opsForZSet().incrementScore(rankKey, userId, rewardPoints);
            ops.expire(rankKey, Duration.ofDays(7));
            return ops.exec();
        });
    }

    public String getTaskStatus(String userId, String taskId) {
        return template.opsForValue().get(L408Keys.taskStatus(userId, taskId));
    }

    public Long getUserPoints(String userId) {
        String raw = template.opsForValue().get(L408Keys.pointsUser(userId));
        return raw == null ? null : Long.parseLong(raw);
    }

    public Set<String> getTopUsers(String date, int n) {
        return template.opsForZSet().reverseRange(L408Keys.taskRank(date), 0, n - 1);
    }

    public void clearTaskData(String userId, String taskId, String date) {
        template.delete(L408Keys.taskStatus(userId, taskId));
        template.delete(L408Keys.pointsUser(userId));
        template.delete(L408Keys.taskRank(date));
    }
}
