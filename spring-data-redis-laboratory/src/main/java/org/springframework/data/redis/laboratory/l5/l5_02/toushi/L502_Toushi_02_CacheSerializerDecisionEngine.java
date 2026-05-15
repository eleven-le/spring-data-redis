package org.springframework.data.redis.laboratory.l5.l5_02.toushi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 偷师 2：把"序列化器选型决策树"落到代码。
 * <p>
 * 对应文档：L5-02 → §7 选型决策树。
 * <p>
 * 真实背景：
 * 一个有规模的 C 端公司,缓存接入会建一个"缓存平台"或"治理 SDK"。
 * 业务方提交一个 CacheDataProfile（数据画像）,平台输出推荐的 SerializerChoice。
 * 这一节就是这个决策引擎的内核——
 * <p>
 * <b>核心理念：选型不是喜好,而是约束条件推导出来的结果。</b>
 * 输入的是业务约束（是否跨服务、是否跨语言、是否多态、是否性能敏感、字段是否稳定…）,
 * 输出的是序列化器选择 + 理由。下次有人问"为什么不用 JDK",
 * 引擎能甩出一行"因为你勾了跨服务共享 + 长 TTL,JDK 在这两个轴上线上必炸"。
 * <p>
 * 不需要 Redis,纯决策逻辑。
 */
public class L502_Toushi_02_CacheSerializerDecisionEngine {

    // ─────────────────────── 输入：缓存数据画像 ───────────────────────

    /**
     * 缓存数据画像——业务约束的结构化描述。
     * 字段对应 §7 决策树的 6 个分支问题。
     */
    public static class CacheDataProfile {
        public final String name;
        public final DataShape shape;
        public final boolean isKey;             // 是否 key（含 hashKey）
        public final boolean crossService;      // 是否多服务共享
        public final boolean crossLanguage;     // 是否跨语言
        public final boolean polymorphic;       // 字段是否多态/含 Object
        public final boolean performanceCritical; // 是否极致性能/体积敏感
        public final boolean stableDto;         // 是否稳定 DTO 已固化
        public final long ttlSeconds;           // TTL 长度,长 TTL 容忍兼容性差的序列化器

        public CacheDataProfile(String name, DataShape shape, boolean isKey,
                                boolean crossService, boolean crossLanguage,
                                boolean polymorphic, boolean performanceCritical,
                                boolean stableDto, long ttlSeconds) {
            this.name = name; this.shape = shape; this.isKey = isKey;
            this.crossService = crossService; this.crossLanguage = crossLanguage;
            this.polymorphic = polymorphic; this.performanceCritical = performanceCritical;
            this.stableDto = stableDto; this.ttlSeconds = ttlSeconds;
        }
    }

    public enum DataShape {
        /** 纯字符串、数字、Token、状态、计数器。 */ SIMPLE_VALUE,
        /** 复杂对象。 */ COMPLEX_OBJECT,
        /** 上游已序列化的 byte[]（Protobuf/Kryo）。 */ RAW_BYTES
    }

    // ─────────────────────── 输出：选择 + 理由 ───────────────────────

    public enum Serializer {
        STRING("StringRedisSerializer"),
        GENERIC_JSON("GenericJackson2JsonRedisSerializer"),
        TYPED_JSON("Jackson2JsonRedisSerializer<DTO>"),
        BYTE_ARRAY("ByteArrayRedisSerializer"),
        PROTOBUF_OR_CUSTOM("Protobuf / Kryo / 自定义二进制 (L5-05)");

        public final String displayName;
        Serializer(String displayName) { this.displayName = displayName; }
    }

    public static class SerializerChoice {
        public final Serializer choice;
        public final List<String> reasons;

        public SerializerChoice(Serializer choice, List<String> reasons) {
            this.choice = choice; this.reasons = reasons;
        }
        @Override public String toString() {
            return String.format("→ %-50s  reasons:%n   - %s",
                    choice.displayName, String.join("\n   - ", reasons));
        }
    }

    // ─────────────────────── 决策引擎 ───────────────────────

    /**
     * 决策引擎。沿着 §7 决策树一步一步推。
     * 每一条 reason 都是"为什么不选其它"——这样后续 Code Review 翻历史决策能复现思路。
     */
    public static class DecisionEngine {
        public SerializerChoice recommend(CacheDataProfile p) {
            List<String> reasons = new ArrayList<>();

            // ① Key 路径——闭眼 String
            if (p.isKey) {
                reasons.add("Key 类型：redis-cli SCAN/KEYS/MIGRATE 都要求可读字节");
                reasons.add("跨语言最低公分母,二进制 key 会让运维和 Ops 平台失能");
                return new SerializerChoice(Serializer.STRING, reasons);
            }

            // ② 简单值——也走 String
            if (p.shape == DataShape.SIMPLE_VALUE) {
                reasons.add("简单值（Token/计数器/状态）JSON 化反而增加体积和理解成本");
                reasons.add("INCR 等原子命令必须是 String 数字,JSON 包一层就废了");
                return new SerializerChoice(Serializer.STRING, reasons);
            }

            // ③ 已是 byte[]——透传
            if (p.shape == DataShape.RAW_BYTES) {
                reasons.add("调用方已握 byte[]（Protobuf/Kryo/压缩字节）,框架不应再序列化一次");
                reasons.add("ByteArrayRedisSerializer 零开销透传,把责任明确甩给上游");
                return new SerializerChoice(Serializer.BYTE_ARRAY, reasons);
            }

            // ④ 复杂对象 + 极致性能 → 自定义二进制
            if (p.performanceCritical) {
                reasons.add("性能/体积敏感(Feed/秒杀/召回): JSON 在体积和 CPU 上都顶不住");
                reasons.add("推荐 Protobuf(跨语言)或 Kryo(JVM 内)——详见 L5-05");
                return new SerializerChoice(Serializer.PROTOBUF_OR_CUSTOM, reasons);
            }

            // ⑤ 复杂对象 + 跨服务/跨语言 → Typed JSON DTO
            if (p.crossService || p.crossLanguage) {
                reasons.add("跨" + (p.crossLanguage ? "语言" : "服务") + "共享缓存,禁止任何带 Java 类名元数据的序列化器");
                reasons.add("Generic 的 @class 硬绑 Java 类路径,包名重构必炸");
                reasons.add("用稳定 DTO 把缓存协议和 Java 类路径解耦,DTO 上线即冻结字段");
                if (p.polymorphic) {
                    reasons.add("⚠️ 多态场景,Typed 单 Class 会丢子类型,需 @JsonTypeInfo + 自定义 TypeId 注解(协议管理)");
                }
                return new SerializerChoice(Serializer.TYPED_JSON, reasons);
            }

            // ⑥ 单服务内部 + 多态 → Generic JSON
            if (p.polymorphic) {
                reasons.add("单服务内部缓存,且字段含多态/Object/List<父接口>");
                reasons.add("Generic 的 @class 元信息正好能在反序列化时还原真实子类");
                if (p.ttlSeconds > 86_400L * 7) {
                    reasons.add("⚠️ 注意 TTL 超 7 天 + Generic：类被 Move 会导致老数据反序列化失败,需要约束包名稳定");
                }
                return new SerializerChoice(Serializer.GENERIC_JSON, reasons);
            }

            // ⑦ 单服务内部 + 稳定 DTO → Typed JSON
            if (p.stableDto) {
                reasons.add("单服务但 DTO 字段稳定,无需 @class 元信息,JSON 更干净体积更小");
                return new SerializerChoice(Serializer.TYPED_JSON, reasons);
            }

            // ⑧ 默认兜底：单服务复杂对象 → Generic
            reasons.add("单服务内部复杂对象,字段可能演进,Generic 容错性最好");
            reasons.add("⚠️ 如果未来需要跨服务共享,届时切到 Typed DTO 并做数据迁移");
            return new SerializerChoice(Serializer.GENERIC_JSON, reasons);
        }
    }

    // ─────────────────────── 演示：6 种典型 C 端业务画像 ───────────────────────

    public static void main(String[] args) {
        DecisionEngine engine = new DecisionEngine();
        List<CacheDataProfile> profiles = Arrays.asList(
                // 1. 任意一个 Redis Key
                new CacheDataProfile("[Redis Key] user:profile:{uid}",
                        DataShape.SIMPLE_VALUE, true, false, false, false, false, false, 0),

                // 2. 登录 Token——简单值
                new CacheDataProfile("[Value] 登录 Token",
                        DataShape.SIMPLE_VALUE, false, false, false, false, false, false, 1800),

                // 3. 单服务内部用户画像——多态,Generic
                new CacheDataProfile("[Value] 单服务内部用户画像",
                        DataShape.COMPLEX_OBJECT, false, false, false, true, false, false, 86400 * 7),

                // 4. 商品详情快照——多服务共享,Typed
                new CacheDataProfile("[Value] 跨服务共享商品详情快照",
                        DataShape.COMPLEX_OBJECT, false, true, false, false, false, true, 3600),

                // 5. Feed 召回结果——极致性能
                new CacheDataProfile("[Value] Feed 召回结果(高 QPS,大体积)",
                        DataShape.COMPLEX_OBJECT, false, false, false, false, true, false, 60),

                // 6. 风控特征 byte[]——上游已 Protobuf 编码
                new CacheDataProfile("[Value] 风控特征(上游 Protobuf 编码)",
                        DataShape.RAW_BYTES, false, true, true, false, false, false, 300),

                // 7. 长 TTL + Generic 风险预警示例
                new CacheDataProfile("[Value] 单服务多态卡片,TTL 30 天",
                        DataShape.COMPLEX_OBJECT, false, false, false, true, false, false, 86400L * 30)
        );

        for (CacheDataProfile p : profiles) {
            System.out.println();
            System.out.println("== " + p.name + " ==");
            System.out.println(engine.recommend(p));
        }

        System.out.println();
        System.out.println("→ 偷师结论：");
        System.out.println("  序列化器选型 = 约束条件求解。");
        System.out.println("  把决策逻辑沉到引擎里,团队成员不会再凭直觉乱选,Code Review 也能 reason 化。");
        System.out.println("  这是 RedisSerializer 家族多实现背后的工程价值——不是\"提供多种选择\",");
        System.out.println("  而是\"给每种业务约束都备一把合适的工具\"。");
    }
}
