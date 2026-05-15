package org.springframework.data.redis.laboratory.l4.l4_10.toushi;

import java.util.EnumMap;
import java.util.Map;

/**
 * 偷师 ④：返回码解释器（Result Code Interpreter）。
 * <p>
 * Lua 脚本返回的就是裸 Long（1/0/-1...），如果业务到处 {@code if (code == 1)}，脚本演化必崩。
 * Spring 在 ExceptionTranslator / ResultExtractor 上反复使用了同一招：把<b>原始信号</b>翻译成<b>领域语义</b>。
 * <p>
 * 业务侧任何"枚举返回码 → 业务结果"的映射都该用一个解释器收口：
 * <ul>
 *   <li>返回码集中定义；</li>
 *   <li>解释逻辑集中维护；</li>
 *   <li>业务代码只看到 {@code BusinessActionResult}，看不到原始码。</li>
 * </ul>
 */
public class ResultCodeInterpreterDesignDemo {

    public enum ResultCode {
        SUCCESS, REJECTED, NOT_FOUND, DUPLICATE, INVALID_ARGS, SYSTEM_ERROR, UNKNOWN;
    }

    public static class BusinessActionResult {
        public final ResultCode code;
        public final String message;
        public BusinessActionResult(ResultCode code, String message) {
            this.code = code; this.message = message;
        }
        @Override public String toString() { return code + "(" + message + ")"; }
    }

    public static class ResultCodeInterpreter {
        private final Map<Long, ResultCode> table = new java.util.HashMap<>();
        private final Map<ResultCode, String> messages = new EnumMap<>(ResultCode.class);

        public ResultCodeInterpreter() {
            table.put(1L, ResultCode.SUCCESS);
            table.put(0L, ResultCode.REJECTED);
            table.put(-1L, ResultCode.NOT_FOUND);
            table.put(-2L, ResultCode.DUPLICATE);
            table.put(-3L, ResultCode.INVALID_ARGS);
            table.put(-9L, ResultCode.SYSTEM_ERROR);
            messages.put(ResultCode.SUCCESS, "ok");
            messages.put(ResultCode.REJECTED, "条件不满足");
            messages.put(ResultCode.NOT_FOUND, "数据不存在");
            messages.put(ResultCode.DUPLICATE, "重复操作");
            messages.put(ResultCode.INVALID_ARGS, "参数非法");
            messages.put(ResultCode.SYSTEM_ERROR, "系统/脚本异常");
            messages.put(ResultCode.UNKNOWN, "未知返回码");
        }

        public BusinessActionResult interpret(Long raw) {
            ResultCode c = raw == null ? ResultCode.SYSTEM_ERROR : table.getOrDefault(raw, ResultCode.UNKNOWN);
            return new BusinessActionResult(c, messages.get(c));
        }
    }

    public static void main(String[] args) {
        ResultCodeInterpreter ip = new ResultCodeInterpreter();
        for (Long raw : new Long[]{1L, 0L, -1L, -2L, -3L, -9L, 42L, null}) {
            System.out.println("raw=" + raw + " -> " + ip.interpret(raw));
        }
    }
}
