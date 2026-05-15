package org.springframework.data.redis.laboratory.l4.l4_03.string;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.laboratory.l4.l4_03.L403Keys;

import java.time.Duration;
import java.util.UUID;

/**
 * 登录 Token / Session 场景。
 * <p>
 * 双向索引：
 * <ul>
 *   <li>l4:03:token:{token}         -> userId      （拿 token 查谁登录的）</li>
 *   <li>l4:03:user:token:{userId}   -> token       （踢人下线时按 userId 反查）</li>
 * </ul>
 * <p>
 * 单端登录策略：
 * <ul>
 *   <li>登录时若 user:token:{uid} 已存在，先把旧 token 删除（踢下线）；</li>
 *   <li>再写入新 token + 新映射。</li>
 * </ul>
 * 多端登录：把 user:token:{uid} 改成 Hash（field=端类型，value=token）即可。
 */
public class L403TokenSessionScenario {

    public static final Duration TOKEN_TTL = Duration.ofHours(2);

    private final ValueOperations<String, String> ops;
    private final StringRedisTemplate template;

    public L403TokenSessionScenario(StringRedisTemplate template) {
        this.template = template;
        this.ops = template.opsForValue();
    }

    private static String tokenKey(String token) {
        return L403Keys.TOKEN + token;
    }

    private static String userKey(String userId) {
        return L403Keys.USER_TOKEN + userId;
    }

    /**
     * 登录：踢旧 token + 颁发新 token + 双向写映射。
     */
    public String login(String userId) {
        kickOut(userId); // 单端登录：踢旧的
        String token = UUID.randomUUID().toString().replace("-", "");
        ops.set(tokenKey(token), userId, TOKEN_TTL);
        ops.set(userKey(userId), token, TOKEN_TTL);
        return token;
    }

    public String getUserIdByToken(String token) {
        return ops.get(tokenKey(token));
    }

    /**
     * 续期：每次活跃请求后调用，让 TTL 滚动。
     * 注意：续期是按 token 续，不是按业务时间硬编码。
     */
    public boolean refreshToken(String token) {
        String uid = ops.get(tokenKey(token));
        if (uid == null) return false;
        template.expire(tokenKey(token), TOKEN_TTL);
        template.expire(userKey(uid), TOKEN_TTL);
        return true;
    }

    public void logout(String token) {
        String uid = ops.get(tokenKey(token));
        template.delete(tokenKey(token));
        if (uid != null) {
            template.delete(userKey(uid));
        }
    }

    /**
     * 踢下线：按 userId 反查 token，把两端都删了。
     */
    public void kickOut(String userId) {
        String oldToken = ops.get(userKey(userId));
        if (oldToken != null) {
            template.delete(tokenKey(oldToken));
        }
        template.delete(userKey(userId));
    }
}
