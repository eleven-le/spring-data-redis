package org.springframework.data.redis.laboratory.l4.l4_10.script;

import java.util.HashMap;
import java.util.Map;

/**
 * 脚本返回码统一解释器。
 * <p>
 * 为什么独立成类：脚本返回的是裸 Long（1/0/-1/-2/-3/-9），如果业务代码到处写
 * {@code if (code == 1)} 是典型的 magic number 反模式——脚本演化时业务必崩。
 * 这里把"码 → 语义"的映射收口到一处，类似 Spring 的 ExceptionTranslator 思想。
 * <p>
 * 见偷师包 {@code toushi/ResultCodeInterpreterDesignDemo} 的抽象版。
 */
public final class L410ScriptResultCodeInterpreter {

    public enum ScriptResultCode {
        SUCCESS(1L, "成功"),
        REJECTED(0L, "条件不满足/拒绝"),
        NOT_FOUND(-1L, "数据不存在 / 未初始化"),
        DUPLICATE(-2L, "重复操作"),
        INVALID_ARGS(-3L, "参数非法"),
        SYSTEM_ERROR(-9L, "系统/脚本异常"),
        UNKNOWN(Long.MIN_VALUE, "未知返回码");

        public final long code;
        public final String desc;

        ScriptResultCode(long code, String desc) {
            this.code = code;
            this.desc = desc;
        }
    }

    private static final Map<Long, ScriptResultCode> INDEX = new HashMap<>();

    static {
        for (ScriptResultCode c : ScriptResultCode.values()) {
            INDEX.put(c.code, c);
        }
    }

    private L410ScriptResultCodeInterpreter() {
    }

    public static ScriptResultCode interpret(Long raw) {
        if (raw == null) {
            return ScriptResultCode.SYSTEM_ERROR;
        }
        return INDEX.getOrDefault(raw, ScriptResultCode.UNKNOWN);
    }

    public static String explain(Long raw) {
        ScriptResultCode c = interpret(raw);
        return c == ScriptResultCode.UNKNOWN
                ? "UNKNOWN(" + raw + ")"
                : c.name() + "(" + raw + ", " + c.desc + ")";
    }

    public static boolean isSuccess(Long raw) {
        return raw != null && raw == ScriptResultCode.SUCCESS.code;
    }

    /**
     * 脚本失败必须抛业务异常的场景使用。<br>
     * 注意：这是个示例风格，真实业务请用领域异常 + 上下文，不要直接抛 RuntimeException。
     */
    public static void requireSuccess(Long raw) {
        if (!isSuccess(raw)) {
            throw new IllegalStateException("Script failed: " + explain(raw));
        }
    }
}
