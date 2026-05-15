package org.springframework.data.redis.laboratory.l4.l4_04.set;

import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_04.L404Keys;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 抽奖池：报名 SADD，公示候选 SRANDMEMBER（非破坏），开奖 SPOP（破坏性，天然不重复中奖）。
 * <p>
 * 真实活动避坑：
 * 1) 中奖结果必须落 DB，Redis 只是抽奖载体；活动 Set 过期一切就丢了。
 * 2) 大额奖项要做风控（同 IP / 同设备 / 同手机号），Set 只负责"是谁"，不负责"该不该中"。
 * 3) 高并发开奖（万人秒级抽 N 个）建议 Lua 把 SRANDMEMBER + SPOP + DB 落库 + 库存扣减捆成原子。
 * 4) SPOP 是破坏性的，活动复盘时 Set 已空，复盘只能查 DB 中奖记录。
 */
public class L404LotterySetScenario {

    private final SetOperations<String, String> ops;
    private final StringRedisTemplate template;

    public L404LotterySetScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForSet();
    }

    private String key(String activityId) {
        return L404Keys.LOTTERY_POOL + activityId;
    }

    public boolean join(String activityId, String userId) {
        Long added = ops.add(key(activityId), userId);
        return added != null && added > 0;
    }

    public Boolean isJoined(String activityId, String userId) {
        return ops.isMember(key(activityId), userId);
    }

    /**
     * 不删除，仅看候选。前端公示"参与名单滚动"。
     */
    public List<String> randomCandidates(String activityId, int count) {
        Set<String> set = ops.distinctRandomMembers(key(activityId), count);
        return set == null ? Collections.emptyList() : new java.util.ArrayList<>(set);
    }

    /**
     * 开奖一人：SPOP，破坏性，确保不重复中奖。
     */
    public String drawOne(String activityId) {
        return ops.pop(key(activityId));
        // 断点: DefaultSetOperations.pop → connection.setCommands().sPop
    }

    /**
     * 开奖多人：SPOP count。
     */
    public List<String> drawMany(String activityId, int count) {
        return ops.pop(key(activityId), count);
    }

    public Long poolSize(String activityId) {
        return ops.size(key(activityId));
    }

    public void clear(String activityId) {
        template.delete(key(activityId));
    }
}
