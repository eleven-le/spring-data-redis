# L3-04 Lettuce 连接池模型 实验包

> 本目录是《L3-04 Lettuce 连接池模型（GenericObjectPool）》的本地实验代码。
> 完整源码全部在本地，Notion 上只放路径与说明。

## 实验清单

| 序号 | 类名 | 角色 | 是否需要真实 Redis |
| --- | --- | --- | --- |
| ① | `GenericObjectPoolBasicLab` | 把 borrow / return / 复用 三件事跑透 | ❌ |
| ② | `PoolMaxActiveWaitTimeoutLab` | max-active 打满 + max-wait 超时 = "Redis 没事但接口全慌" | ❌ |
| ③ | `PoolLeakExhaustedLab` | 忘记 returnObject = 连接泄漏，对照正确写法 | ❌ |
| ④ | `PoolIdleConfigLab` | maxIdle / minIdle / 空闲驱逐对池容量的影响 | ❌ |
| ⑤ | `PoolValidateOnBorrowLab` | testOnBorrow 是怎么救命的（拿到坏连接 → 抛 vs 自动换新） | ❌ |
| ⑥ | `MiniLettuceConnectionPoolSimulation` | 一文压缩 Factory / Provider(共享/池化) / 阻塞 vs 普通命令 的全部协作 | ❌ |
| 偷师 | `toushi/ExternalApiPoolDemo` | 把整套设计搬到"第三方风控 API 客户端池" | ❌ |

> ⚠️ 本章 6 个实验全部不依赖真实 Redis。这是有意为之：
> 学的是连接池模型，不是 Redis 命令。把外部依赖砍掉，学员每跑一次都能稳定看到现象。

## 公共物料

| 文件 | 作用 |
| --- | --- |
| `MockRedisConnection` | 一个有 id / alive / 创建-关闭开销的"连接对象"，所有实验共用 |
| `MockRedisConnectionFactory` | 实现 `BasePooledObjectFactory<MockRedisConnection>`，告诉池怎么 CREATE / VALIDATE / DESTROY |

## 推荐学习顺序

1. **实验①** — 看清最朴素的 borrow / return / 复用
2. **实验②** — 看到"max-active 打满 + max-wait 超时"长什么样（生产事故缩影）
3. **实验③** — 复盘"忘记归还"导致的泄漏，以及 try / finally 正解
4. **实验④** — 调 maxIdle/minIdle/驱逐参数，理解资源回收策略
5. **实验⑤** — 体会 testOnBorrow 的代价 vs 收益
6. **实验⑥** — 把上面 5 个实验拼起来，得到 Lettuce 风格的小型连接池骨架
7. **偷师 demo** — 把这套设计搬到自己的"第三方 API 客户端池"

## 各实验预期现象

### ① GenericObjectPoolBasicLab
```
=== 1. 单借单还：复用同一个连接 ===
[factory] CREATE  -> MockRedisConnection#1
borrow #1: MockRedisConnection#1   (active=1, idle=0)
return #1:                         (active=0, idle=1)
borrow #2: MockRedisConnection#1   ← 直接复用，没 CREATE
borrow #3: MockRedisConnection#1   ← 还是它

=== 2. 同时持有多条：触发新建 ===
[factory] CREATE  -> MockRedisConnection#2
[factory] CREATE  -> MockRedisConnection#3
now:  active=3, idle=0
after return all: active=0, idle=3
```

### ② PoolMaxActiveWaitTimeoutLab
```
[t0] borrowed MockRedisConnection#1 in 1ms
[t1] borrowed MockRedisConnection#2 in 1ms
[t2] BORROW FAILED ... NoSuchElementException: Timeout waiting for idle object
[t3] BORROW FAILED ... NoSuchElementException: Timeout waiting for idle object
[t4] BORROW FAILED ... NoSuchElementException: Timeout waiting for idle object

ok      = 2
timeout = 3   ← 这就是生产里"接口大面积超时但 Redis 没事"的最小复现
```

### ③ PoolLeakExhaustedLab
```
============ 1. 错误写法：忘记 returnObject ============
[biz-1] borrowed MockRedisConnection#1
[biz-2] borrowed MockRedisConnection#2
[biz-3] 借不到连接：... Timeout waiting for idle object
>> 池状态：active=2, idle=0   ← active 没归零，就是泄漏的指纹

============ 2. 正确写法：try / finally returnObject ============
... 连续 5 次 borrow + return，全部复用 conn#1
>> 池状态：active=0, idle=1   ← active=0 = 健康
```

### ④ PoolIdleConfigLab
```
=== 模拟活动峰值：一次性借 6 条 ===
active=6 idle=0

=== 活动结束：6 条全归还，但 maxIdle=3 ===
立刻看 idle 数量 = 3   ← 多出来的 (6-3) 条会立刻 DESTROY

=== 等 3 秒，看驱逐线程把 idle 缩到 minIdle=2 ===
3 秒后 idle = 2       ← 长期没用的 idle 会被驱逐到 minIdle 为止
```

### ⑤ PoolValidateOnBorrowLab
```
============ A. testOnBorrow = false（默认） ============
>> 业务拿到 MockRedisConnection#1[BROKEN]
>> ❌ 业务踩到坏连接：IllegalStateException: conn#1 is dead

============ B. testOnBorrow = true ============
[factory] VALIDATE -> MockRedisConnection#2[BROKEN]  result=false
[factory] DESTROY -> MockRedisConnection#2[BROKEN]
[factory] CREATE  -> MockRedisConnection#3
>> exec result = OK(conn#3 -> INCR x)   ← 池自己换了一条新的，业务无感
```

### ⑥ MiniLettuceConnectionPoolSimulation
```
============ 场景①：普通命令走共享 ============
[shared] lazy create -> MockRedisConnection#1
✅ 1000 个普通命令完成，ok=1000   ← 1000 个线程共用 1 条连接

============ 场景②：阻塞命令走池化 ============
[BLPOP-0] got MockRedisConnection#2 (这是一条独立连接，不会污染共享通道)
[BLPOP-1] got MockRedisConnection#3 ...
[BLPOP-2] got MockRedisConnection#4 ...
[BLPOP-3] got MockRedisConnection#2 (前面的归还后被复用)
...
✅ 5 条 BLPOP 各自独占连接，互不影响
```

### 偷师 ExternalApiPoolDemo
```
业务线程数  = 50
成功        = 8~12       ← 同一时刻最多 4 个，剩下的能进就进
快速失败    = 38~42      ← max-wait=200ms 内借不到立刻失败
active/idle = 0/4

关键观察：实际打到 Tongdun 的并发被池子卡在 maxActive=4 之内，下游被你保护了。
```

## 运行方式

IDEA 里直接 Run 各个 `*Lab.main`，或：

```bash
mvn -pl spring-data-redis-laboratory exec:java \
    -Dexec.mainClass="org.springframework.data.redis.laboratory.l3_04.GenericObjectPoolBasicLab"
```

## Maven 依赖

本模块继承父 POM。父 POM 把 `commons-pool2` 设成了 `optional=true`，所以本子模块 POM
显式声明了：

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

无需 Spring Boot，无需真实 Redis。
