package org.springframework.data.redis.laboratory.l4.l4_03.debug;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.laboratory.l4.l4_03.config.L403RedisConfig;
import org.springframework.data.redis.laboratory.l4.l4_03.string.L403CounterScenario;
import org.springframework.data.redis.laboratory.l4.l4_03.string.L403IdempotentScenario;
import org.springframework.data.redis.laboratory.l4.l4_03.string.L403SmsCodeScenario;
import org.springframework.data.redis.laboratory.l4.l4_03.string.L403StringBasicOperationsLab;
import org.springframework.data.redis.laboratory.l4.l4_03.string.L403TokenSessionScenario;

/**
 * String 系列 Debug 入口。
 * <p>
 * 推荐断点路径（任选一行打条件断点，Step Into 即可走进 Spring Data Redis 源码）：
 * <ul>
 *   <li>{@code RedisTemplate#opsForValue}（看 ValueOperations 子门面如何懒生成）</li>
 *   <li>{@code DefaultValueOperations#set}（进入 execute(callback) 模板方法）</li>
 *   <li>{@code RedisTemplate#execute(RedisCallback, boolean, boolean)}（看资源管理 finally）</li>
 *   <li>{@code RedisConnectionUtils#doGetConnection}（看 ConnectionHolder 复用）</li>
 *   <li>{@code LettuceConnection#stringCommands}（看 byte[] 命令分流）</li>
 * </ul>
 * <p>
 * 学习建议：
 * 1) 第一遍跑通 demoSet 看完整链路；
 * 2) 第二遍在 verifyCode 里打断点，对比"业务方法 → ValueOperations.get → connection.get"；
 * 3) 第三遍在 SmsCode.checkSendLimit 里打断点，观察 increment + expire 两步如何变成两次 RTT。
 */
public class L403StringDebugMain {

    public static void main(String[] args) {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(L403RedisConfig.class)) {

            StringRedisTemplate template = ctx.getBean(StringRedisTemplate.class);

            System.out.println("════════ ① 基础命令全家桶 ════════");
            new L403StringBasicOperationsLab(template).runAll();

            System.out.println("\n════════ ② 短信验证码 ════════");
            L403SmsCodeScenario sms = new L403SmsCodeScenario(template);
            String phone = "13800000000";
            sms.clearCode(phone);
            String code = sms.sendCode(phone);
            System.out.println("生成验证码: " + code);
            System.out.println("校验错误码: " + sms.verifyCode(phone, "000000"));
            String code2 = sms.sendCode(phone);
            System.out.println("再次发送 (走限流): " + code2);
            System.out.println("校验正确码: " + sms.verifyCode(phone, code2));
            sms.clearCode(phone);

            System.out.println("\n════════ ③ 登录 Token ════════");
            L403TokenSessionScenario session = new L403TokenSessionScenario(template);
            String t1 = session.login("u-1001");
            System.out.println("第一次登录 token=" + t1);
            String t2 = session.login("u-1001"); // 单端登录：旧 token 应失效
            System.out.println("第二次登录 token=" + t2 + " 旧token还能查到uid? " + session.getUserIdByToken(t1));
            session.logout(t2);

            System.out.println("\n════════ ④ 计数器 ════════");
            L403CounterScenario counter = new L403CounterScenario(template);
            for (int i = 0; i < 5; i++) counter.increaseViewCount("item-1");
            System.out.println("浏览数=" + counter.getCount("l4:03:counter:view:item-1"));

            System.out.println("\n════════ ⑤ 幂等标记 ════════");
            L403IdempotentScenario idem = new L403IdempotentScenario(template);
            String reqId = "req-" + System.currentTimeMillis();
            System.out.println("第一次提交: " + idem.executeOnce(reqId, () -> System.out.println("  → 真实业务执行"))); // true
            System.out.println("重复提交  : " + idem.executeOnce(reqId, () -> System.out.println("  → 不应该被调用"))); // false
            idem.clearMark(reqId);

            System.out.println("\n✅ String 链路全跑通。Step Into ops.set 看源码。");
        }
    }
}
