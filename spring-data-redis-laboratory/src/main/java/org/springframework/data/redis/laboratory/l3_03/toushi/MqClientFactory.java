package org.springframework.data.redis.laboratory.l3_03.toushi;

/**
 * 偷师 LettuceConnectionFactory：
 *   - 把"创建昂贵客户端 / 连接 / 监听器"的逻辑收口在工厂里
 *   - 配置（broker 地址、ak/sk、topic 命名空间、超时）在工厂初始化时一次到位
 *   - 业务代码只跟工厂打交道，永远拿不到底层 SDK 实例
 * <p>
 * 这就是 Spring 抽象的"工厂 = 配置边界 + 生命周期边界"的双重价值。
 */
public interface MqClientFactory {

    /** 启动：建连接、建心跳、注册客户端到 broker（对标 LettuceConnectionFactory#afterPropertiesSet） */
    void start();

    /**
     * 拿一个共享生产者：业务高并发发消息场景默认走这里。
     * 偷师对象：{@code SharedConnection.getConnection()}
     */
    MqProducer getSharedProducer();

    /**
     * 拿一个专用消费者：每次新建，一个订阅一个连接。
     * 偷师对象：{@code asyncDedicatedConnection} 创建路径
     */
    MqConsumer createDedicatedConsumer(String groupId);

    /** 资源回收（对标 LettuceConnectionFactory#destroy） */
    void shutdown();
}
