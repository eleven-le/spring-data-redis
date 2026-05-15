package org.springframework.data.redis.laboratory.l4.l4_10.toushi;

import java.security.MessageDigest;

/**
 * 偷师 ①：脚本对象化（Script as a first-class object）。
 * <p>
 * Spring Data Redis 用 {@code RedisScript / DefaultRedisScript} 把"脚本文本 + sha1 + resultType"
 * 三件套封装成对象，业务方法签名永远只看到 {@code RedisScript} 接口，看不到 EVAL/EVALSHA、字符串拼接。
 * <p>
 * 把这个抽象搬到自己业务里：当你有"业务规则脚本/动态规则/工作流模板/SQL 片段"等可复用的<b>行为定义</b>时，
 * 都应该把它对象化——而不是在业务代码里散落字符串拼接。这样：
 * <ol>
 *   <li>规则可以注册到容器统一管理；</li>
 *   <li>规则可以版本化（fingerprint = sha1）；</li>
 *   <li>规则可以独立测试；</li>
 *   <li>调用方拿到的是接口，不感知规则文本。</li>
 * </ol>
 */
public class ScriptObjectDesignDemo {

    public enum ScriptResultType { LONG, BOOLEAN, LIST, VALUE }

    /** 抽象的脚本定义接口（脱掉 Redis 业务细节后的纯结构）。 */
    public interface ScriptDefinition<T> {
        String getScriptText();
        String getSha1();
        Class<T> getResultType();
    }

    /** 默认实现：懒加载 sha1，避免冷启动开销。 */
    public static class DefaultScriptDefinition<T> implements ScriptDefinition<T> {
        private final String text;
        private final Class<T> resultType;
        private volatile String sha1;

        public DefaultScriptDefinition(String text, Class<T> resultType) {
            this.text = text;
            this.resultType = resultType;
        }

        @Override public String getScriptText() { return text; }
        @Override public Class<T> getResultType() { return resultType; }

        @Override public String getSha1() {
            String s = sha1;
            if (s == null) {
                synchronized (this) {
                    if (sha1 == null) {
                        try {
                            MessageDigest md = MessageDigest.getInstance("SHA-1");
                            byte[] dig = md.digest(text.getBytes());
                            StringBuilder sb = new StringBuilder(dig.length * 2);
                            for (byte b : dig) sb.append(String.format("%02x", b));
                            sha1 = sb.toString();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    s = sha1;
                }
            }
            return s;
        }
    }

    public static void main(String[] args) {
        ScriptDefinition<Long> rule = new DefaultScriptDefinition<>(
                "if score >= threshold then return 1 else return 0 end",
                Long.class);
        System.out.println("text = " + rule.getScriptText());
        System.out.println("sha1 = " + rule.getSha1());
        System.out.println("type = " + rule.getResultType().getSimpleName());
        // ⇒ 业务方法签名只暴露 ScriptDefinition,不暴露字符串
    }
}
