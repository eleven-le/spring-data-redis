package org.springframework.data.redis.laboratory.l5.l5_01.profile;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l5.l5_01.L501Keys;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 真实场景：C 端用户画像缓存。
 * <p>
 * 对应文档：L5-01 → §4.3 复杂对象 Value。
 * <p>
 * 选择策略：
 * <ul>
 *   <li>key 用 StringRedisSerializer——SCAN/MIGRATE/线上排查全靠它。</li>
 *   <li>value 用 GenericJackson2JsonRedisSerializer——
 *       画像字段会随业务版本演进（标签从 List&lt;String&gt; 演成 List&lt;Tag&gt;、新增 vipLevel ...），
 *       JSON 容错性远超 JDK 序列化；自带 {@code @class} 元信息可直接还原成 UserProfile 而无需调用方传 Class。</li>
 * </ul>
 * 跨服务共享缓存的风险（必看）：
 * 用 @class 写入的 JSON 形如 {@code {"@class":"...UserProfile","userId":"u-1001",...}}。
 * 一旦下游服务的类名不一样（包名不同、字段不同），反序列化要么报错要么悄悄丢字段。
 * 跨服务共享 Redis 时应改成 Jackson2JsonRedisSerializer + 明确目标类型，或干脆走 contract-first 的 Protobuf。
 * <p>
 * 运行前：本地或测试 Redis 可达，端口/密码见 redis.properties。
 * 运行后观察：
 * <pre>
 *   redis-cli GET l5:01:user:profile:u-1001
 *   --> {"@class":"...UserProfile","userId":"u-1001","nickname":"鹿鸣",...}
 * </pre>
 */
public class L501_02_UserProfileCacheScenario {

    private final RedisTemplate<String, Object> template;
    private final ValueOperations<String, Object> ops;

    public L501_02_UserProfileCacheScenario(RedisTemplate<String, Object> template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    private static String key(String userId) {
        return L501Keys.USER_PROFILE + userId;
    }

    public void cache(UserProfile profile) {
        // 7 天 TTL 是用户画像类缓存的常见兜底；真实业务还要叠加事件驱动失效
        ops.set(key(profile.getUserId()), profile, 7, TimeUnit.DAYS);
    }

    public UserProfile load(String userId) {
        Object o = ops.get(key(userId));
        return (UserProfile) o;
    }

    public void evict(String userId) {
        template.delete(key(userId));
    }

    /**
     * C 端用户画像。字段刻意做得"宽"一点：
     * 基础属性 + 标签 + 等级 + 风控位 + 最近一次更新时间，
     * 这样改一个字段就能模拟"JSON 兼容 / JDK 不兼容"的对比。
     * <p>
     * 实现 Serializable 是为了在 {@link org.springframework.data.redis.laboratory.l5.l5_01.compat.L501_03_JdkSerializerCompatibilityTrap}
     * 里被 JDK 序列化器用同一个 POJO 演示坑。
     */
    public static class UserProfile implements Serializable {
        private static final long serialVersionUID = 1L;

        private String userId;
        private String nickname;
        private Integer vipLevel;
        private List<String> tags;
        private boolean blackListed;
        private LocalDateTime updatedAt;

        public UserProfile() {
        }

        public UserProfile(String userId, String nickname, Integer vipLevel,
                           List<String> tags, boolean blackListed, LocalDateTime updatedAt) {
            this.userId = userId;
            this.nickname = nickname;
            this.vipLevel = vipLevel;
            this.tags = tags;
            this.blackListed = blackListed;
            this.updatedAt = updatedAt;
        }


        public static UserProfile sample(String userId) {
            return new UserProfile(userId,
                    "鹿鸣",
                    3,
                    Arrays.asList("新客", "高客单", "夜间活跃"),
                    false,
                    LocalDateTime.now());
        }

        public String getUserId() {
            return userId;
        }

        public String getNickname() {
            return nickname;
        }

        public Integer getVipLevel() {
            return vipLevel;
        }

        public List<String> getTags() {
            return tags;
        }

        public boolean isBlackListed() {
            return blackListed;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public void setVipLevel(Integer vipLevel) {
            this.vipLevel = vipLevel;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public void setBlackListed(boolean blackListed) {
            this.blackListed = blackListed;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }

        @Override
        public String toString() {
            return "UserProfile{" +
                    "userId='" + userId + '\'' +
                    ", nickname='" + nickname + '\'' +
                    ", vipLevel=" + vipLevel +
                    ", tags=" + tags +
                    ", blackListed=" + blackListed +
                    ", updatedAt=" + updatedAt +
                    '}';
        }
    }
}
