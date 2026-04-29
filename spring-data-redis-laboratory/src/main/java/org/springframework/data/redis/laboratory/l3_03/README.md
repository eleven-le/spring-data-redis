# L3-03 Lettuce 共享连接模型 实验包

> 本目录是《L3-03 Lettuce 共享连接模型（Netty Channel + 多路复用）》的本地实验代码。
> 全部完整源码在本地，Notion 上只保留路径与说明。

## 实验清单

| 序号 | 类名 | 角色 | 是否需要真实 Redis |
| --- | --- | --- | --- |
| ① | `SharedLettuceConnectionIncrLab` | 共享连接 × 1000 线程 INCR，验证多路复用安全 | ✅ |
| ② | `BlockingCommandPoisonSharedConnectionLab` | BLPOP 投毒，亲眼看到共享连接被卡死 | ✅ |
| ③ | `DedicatedConnectionForBlockingCommandLab` | 阻塞命令走专用连接，普通命令零影响 | ✅ |
| ④ | `MiniSharedConnectionProvider` | 极简共享连接 Provider（lazy + double-check） | ✅ |
| ⑤ | `MiniCommandMultiplexingSimulation` | 不依赖 Redis，纯 Java 还原"队列+Future+EventLoop" | ❌（直接跑） |
| 偷师 | `toushi/MqFacadeDemo` | 多 MQ 统一门面骨架（RocketMQ / Kafka） | ❌（直接跑） |

## 运行前置

`spring-data-redis-laboratory/src/main/resources/redis.properties` 决定连哪台 Redis。
本地起一个：

```bash
docker run -d --name redis-lab -p 6379:6379 redis:7
```

如果你**没有 Redis 也想理解多路复用**，先跑实验⑤ `MiniCommandMultiplexingSimulation`
和偷师 demo `toushi/MqFacadeDemo`，它们是纯 Java 的，不需要任何外部依赖。

## 推荐学习顺序

1. 先跑 **实验⑤ Mini Multiplexing Simulation** —— 看清"多路复用是个队列+Future"
2. 再跑 **实验①** —— 真实 Lettuce 验证"1000 线程 / 1 连接 / 0 异常"
3. 跑 **实验②** —— 复现"BLPOP 卡死整条共享连接"的生产事故
4. 跑 **实验③** —— 同样的 BLPOP 放专用连接，普通命令安然无恙
5. 跑 **实验④** —— 拆解 SharedConnection 源码骨架
6. 跑 **偷师 demo** —— 把这套设计搬到自己的多 MQ 客户端

## 各实验预期现象

### ① SharedLettuceConnectionIncrLab
```
线程数         = 1000
成功次数       = 1000
异常次数       = 0
Redis 最终值   = 1000
耗时           ≈ 数百 ms（视 RTT 而定）
```

### ② BlockingCommandPoisonSharedConnectionLab
```
[poisoner] 开始 BLPOP 0 ...
INCR 总线程数 = 50
成功         = 0
超时         = 50    ← 全部 RedisCommandTimeoutException
耗时         ≈ commandTimeout (2000ms)
```
> ⚠️ 这就是生产里"半夜 Redis 全部超时但实例还活着"的最典型故事。

### ③ DedicatedConnectionForBlockingCommandLab
```
INCR 线程数      = 50
成功            = 50
超时            = 0
Redis 计数器值  = 50
sharedConn / dedicatedConn 是两个不同的 Channel
```

### ④ MiniSharedConnectionProvider
```
[provider] 触发 lazy connect... thread=lazy-init-X      ← 只会出现一次
[t=lazy-init-0] got shared @id=12345
[t=lazy-init-1] got shared @id=12345                    ← 全员同一个 instance id
...
```

### ⑤ MiniCommandMultiplexingSimulation
```
============ 场景① ============
✅ 1000 个普通命令完成，耗时 5xxx ms

============ 场景② ============
[event-loop] 收到 BLPOP，开始永久等待 ... 后续命令全部排队等死
❌ INCR 永远等不到，原因 = TimeoutException
```

## 偷师包

`toushi/` 下的代码是把本章的 4 个设计模式
（Singleton / Factory / Strategy-Provider / Adapter）
搬到"多 MQ 统一门面"的演示骨架。
**没有真实 RocketMQ / Kafka 依赖**，直接跑 `MqFacadeDemo` 看输出即可。

## Maven 依赖

本模块继承父 POM，已声明：
- `lettuce-core`（实验 ①②③④ 用）
- `logback-classic`（Lettuce/Netty 日志）

无需额外引入。
