package org.springframework.data.redis.laboratory.l3_08.eviction;

/**
 * <h3>L3-08 / 💀 驱逐阶段 · 实验 3:池子参数怎么定?</h3>
 *
 * <p>这是一个<b>纯讲解类</b>(无 Redis 调用),把 maxTotal / maxIdle / minIdle 三参数的博弈讲清楚。</p>
 *
 * <h4>三参数的角色</h4>
 * <ul>
 *   <li>{@code maxTotal}:池中对象总数上限。<b>压舱石</b>,决定并发上限。</li>
 *   <li>{@code maxIdle}: 空闲对象上限。超过这个数会被 {@code returnObject} 直接销毁。</li>
 *   <li>{@code minIdle}: 空闲对象下限。低于这个数 Evictor 会主动补充(预热)。</li>
 * </ul>
 *
 * <h4>三种典型工作负载的推荐值(以古茗 C 端为参照)</h4>
 *
 * <h5>场景 A:商品详情(读多写少,极高 QPS,但绝大部分走 share-native)</h5>
 * <ul>
 *   <li>{@code shareNativeConnection=true},基本不用池</li>
 *   <li>偶发 dedicated(MULTI/Pub-Sub)的池:{@code maxTotal=8, maxIdle=4, minIdle=2}</li>
 * </ul>
 *
 * <h5>场景 B:库存扣减(高并发,Lua 脚本,share-native 也能跑)</h5>
 * <ul>
 *   <li>同 A,Lua 脚本一次 RTT 完成,share-native 连接安全</li>
 *   <li>慎用 MULTI/EXEC,会把池子压垮</li>
 * </ul>
 *
 * <h5>场景 C:消息消费(BLPOP / XREAD BLOCK,长期占用连接)</h5>
 * <ul>
 *   <li>必须用独立 Factory 走池化,与读路径隔离</li>
 *   <li>{@code maxTotal=消费者数 + 缓冲, maxIdle=消费者数, minIdle=消费者数}</li>
 *   <li>例:30 个消费者 → maxTotal=40, maxIdle=30, minIdle=30</li>
 * </ul>
 *
 * <h4>三个常见踩坑</h4>
 * <ol>
 *   <li>maxTotal 拉到 200 想"高并发兜底" → Redis Server 端 FD 用尽 → CONFIG GET maxclients 也告警</li>
 *   <li>minIdle=0 + 流量低谷 → 突发流量来了要从 0 开始建连,RT 抖动</li>
 *   <li>maxIdle ≪ maxTotal → 高峰过后大量连接被立刻销毁,下个高峰又重建,反复抖动</li>
 * </ol>
 *
 * @author leilei
 * @since 2026-04-30
 */
public class PoolSizingDecisionDemo {

    public static void main(String[] args) {
        System.out.println("📚 这是讲解类,无运行逻辑。请阅读 JavaDoc 与 Notion L3-08 「五、生产决策清单」。");
    }
}
