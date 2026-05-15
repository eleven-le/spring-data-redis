package org.springframework.data.redis.laboratory.l4.l4_08.toushi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 偷师：EXEC mixed results → CommandPlan + ResultMapper。
 * <p>
 * 痛点：业务代码到处 (Long) result.get(0); (Boolean) result.get(1) 散落强转，
 * 一旦命令顺序变更立即出错。
 * <p>
 * 抽象：把"我入队了哪些命令、每条期望什么类型"做成 <b>CommandPlan</b>，
 * 把"如何把 raw List 映射成强类型对象"做成 <b>TransactionResultMapper</b>。
 */
public class TransactionResultMapperDesignDemo {

    /** 命令计划：业务侧约定每条命令的索引和期望类型。 */
    public static final class CommandPlan {
        private final List<Class<?>> expectedTypes = new ArrayList<>();
        public CommandPlan add(Class<?> type) { expectedTypes.add(type); return this; }
        public int size() { return expectedTypes.size(); }
        public Class<?> typeAt(int i) { return expectedTypes.get(i); }
    }

    /** 强类型结果。 */
    public static final class TransactionResult {
        private final Object[] values;
        public TransactionResult(Object[] values) { this.values = values; }
        @SuppressWarnings("unchecked")
        public <T> T at(int i, Class<T> type) {
            Object v = values[i];
            if (v != null && !type.isInstance(v)) {
                throw new ClassCastException("idx=" + i + " expected " + type.getSimpleName()
                        + " but got " + v.getClass().getSimpleName());
            }
            return (T) v;
        }
        @Override public String toString() { return Arrays.toString(values); }
    }

    public static final class TransactionResultMapper {
        public TransactionResult map(CommandPlan plan, List<Object> raw) {
            if (raw == null || raw.isEmpty()) {
                throw new IllegalStateException("EXEC aborted; result is null/empty");
            }
            if (raw.size() != plan.size()) {
                throw new IllegalStateException("plan size " + plan.size() + " != raw size " + raw.size());
            }
            Object[] arr = new Object[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                Object v = raw.get(i);
                Class<?> expected = plan.typeAt(i);
                if (v != null && !expected.isInstance(v)) {
                    throw new IllegalStateException("idx=" + i + " expected "
                            + expected.getSimpleName() + " but raw is " + v.getClass().getSimpleName());
                }
                arr[i] = v;
            }
            return new TransactionResult(arr);
        }
    }

    public static void main(String[] args) {
        // 假设事务入队了：SET / INCR / EXPIRE / GET
        CommandPlan plan = new CommandPlan()
                .add(Boolean.class)   // SET 在 SDR 里通常返回 Boolean
                .add(Long.class)      // INCR
                .add(Boolean.class)   // EXPIRE
                .add(String.class);   // GET

        // 模拟 EXEC raw 返回
        List<Object> raw = Arrays.asList(true, 1L, true, "leiyuhang");

        TransactionResultMapper mapper = new TransactionResultMapper();
        TransactionResult tr = mapper.map(plan, raw);

        Boolean ok1 = tr.at(0, Boolean.class);
        Long after = tr.at(1, Long.class);
        Boolean ok2 = tr.at(2, Boolean.class);
        String name = tr.at(3, String.class);
        System.out.println("[demo] ok1=" + ok1 + ", after=" + after + ", ok2=" + ok2 + ", name=" + name);

        // 演示类型不一致时直接报错——而不是潜伏到运行时业务里
        try {
            mapper.map(plan, Arrays.asList("WRONG", 1L, true, "leiyuhang"));
        } catch (Exception e) {
            System.out.println("[demo] caught planned mismatch: " + e.getMessage());
        }
    }
}
