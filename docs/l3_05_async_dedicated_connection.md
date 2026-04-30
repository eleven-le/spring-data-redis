# L3-05 专用连接 asyncDedicatedConn 创建时机

## 本章学习目标

本章要把 Spring Data Redis 2.7.18 中 LettuceConnection 的 shared / dedicated 分流讲透：普通 GET/SET 为什么可以共享，pipeline / transaction / blocking command 为什么必须隔离，以及 `asyncDedicatedConn` 到底在哪一行代码被懒加载。

完成后你应该能做到：

- 跟断点说清 `RedisTemplate.execute(...) -> RedisConnectionUtils -> LettuceConnectionFactory -> LettuceConnection`。
- 判断一次 RedisTemplate 调用是否会创建 `asyncDedicatedConn`。
- 知道连接池在 `shareNativeConnection=true` 下主要服务哪些 dedicated 路径。
- 避免 C 端高并发链路中因为 pipeline、transaction、blocking command、Cursor 泄漏造成 RT 抖动。
- 把 Template + Callback、Factory、Resource Holder、ThreadLocal 绑定、Lazy Initialization 搬到自己的业务框架里。

## 运行前准备

当前项目是非 Spring Boot Maven 项目：

- 实验模块：`spring-data-redis-laboratory`
- 包名：`org.springframework.data.redis.laboratory.l3_05`
- 当前源码版本：Spring Data Redis `2.7.18`
- Spring Framework：`5.3.31`
- Lettuce：`6.1.10.RELEASE`
- commons-pool2：`2.11.1`

配置文件在：

```text
spring-data-redis-laboratory/src/main/resources/redis.properties
```

默认读取：

```properties
redis.host=127.0.0.1
redis.port=6379
redis.database=0
redis.password=
```

## Redis 本地启动方式建议

```bash
docker run --name redis-lab -p 6379:6379 -d redis:7
```

如果你已经有本地 Redis，只要确认 `redis.properties` 指向正确地址即可。

## Maven 依赖说明

本章不需要新增依赖。`spring-data-redis-laboratory/pom.xml` 已经包含：

```xml
<dependency>
    <groupId>org.springframework.data</groupId>
    <artifactId>spring-data-redis</artifactId>
    <version>${project.version}</version>
</dependency>

<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>${lettuce}</version>
</dependency>

<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

## 推荐断点顺序

1. `RedisTemplate.execute(RedisCallback, boolean, boolean)`
2. `RedisConnectionUtils.getConnection(RedisConnectionFactory, boolean)`
3. `RedisConnectionUtils.doGetConnection(...)`
4. `LettuceConnectionFactory.getConnection()`
5. `LettuceConnection.<init>(StatefulConnection, LettuceConnectionProvider, long, int)`
6. `LettuceConnection.openPipeline()`
7. `LettuceConnection.getAsyncConnection()`
8. `LettuceConnection.getAsyncDedicatedConnection()`
9. `LettuceConnection.doGetAsyncDedicatedConnection()`
10. `LettuceConnection.getOrCreateDedicatedConnection()`
11. `LettuceConnection.close()`
12. `RedisConnectionUtils.releaseConnection(...)`

SCAN / Cursor 额外断点：

1. `RedisTemplate.executeWithStickyConnection(...)`
2. `LettuceKeyCommands.scan(ScanOptions)`
3. `LettuceKeyCommands.doScan(ScanOptions)`
4. 匿名 `LettuceScanCursor.doClose()`

## Demo 怎么运行

IDEA 中直接运行 main 方法，或使用 Maven exec 插件：

```bash
mvn -pl spring-data-redis-laboratory exec:java \
    -Dexec.mainClass="org.springframework.data.redis.laboratory.l3_05.L305NormalSharedConnectionDemo"
```

### 1. L305NormalSharedConnectionDemo

演示普通 GET / SET。

观察：

- `LettuceConnectionFactory.getConnection()` 每次创建 wrapper。
- wrapper 的 `asyncSharedConn != null`。
- `LettuceConnection.getAsyncConnection()` 不进入 `getAsyncDedicatedConnection()`。

### 2. L305PipelineDedicatedConnectionDemo

演示 `executePipelined(...)`。

观察：

- `RedisTemplate.executePipelined(...)` 内部调用 `connection.openPipeline()`。
- `LettuceConnection.openPipeline()` 设置 `isPipelined=true`。
- 当前版本会立即执行 `getOrCreateDedicatedConnection()`。
- 如果启用 pooling factory，会进入 `LettucePoolingConnectionProvider.getConnection(Class)` borrow。

### 3. L305TransactionDedicatedConnectionDemo

演示 `SessionCallback + multi / exec`。

观察：

- `RedisTemplate.execute(SessionCallback)` 会先 `bindConnection(factory, enableTransactionSupport)`。
- `RedisConnectionUtils.doGetConnection(...)` 后续从 `TransactionSynchronizationManager` 取到同一个 holder。
- `LettuceConnection.multi()` 设置 `isMulti=true` 并走 dedicated commands。

### 4. L305ScanCursorStickyConnectionDemo

演示 `scan / Cursor`。

观察：

- `RedisTemplate.scan(...)` 走 `executeWithStickyConnection(...)`。
- 方法返回 Cursor 时没有立即释放 connection。
- try-with-resources 关闭 Cursor 后，`LettuceKeyCommands.doClose()` 触发 connection close。
- 当前版本 SCAN 通常不等于创建 `asyncDedicatedConn`，重点是 sticky connection 生命周期。

### 5. L305DangerousMixedUsageDemo

演示不要随便混用复杂连接语义。

观察：

- SessionCallback 中事务会绑定 holder。
- pipeline 会专用连接。
- scan 在 pipeline / transaction 中会被 `LettuceKeyCommands.doScan(...)` 拒绝。

### 6. L305DebugGuide

只放调试导航 Javadoc。打开 IDEA Quick Documentation 即可按顺序打断点。

### 7. toushi/MiniBusinessDemo

纯 Java 模拟，不需要真实 Redis。

```bash
mvn -pl spring-data-redis-laboratory exec:java \
    -Dexec.mainClass="org.springframework.data.redis.laboratory.l3_05.toushi.MiniBusinessDemo"
```

观察：

- 普通命令使用 shared connection。
- pipeline / transaction / blocking command 第一次触发 dedicated lazy initialization。
- Session 通过 ThreadLocal holder 复用 wrapper。
- wrapper close 时释放 dedicated connection。

## 常见问题

### 1. 为什么我配置了 Lettuce pool，普通 GET/SET 看不到 borrow？

在 `shareNativeConnection=true` 下，普通 GET/SET 默认走共享 native connection。池主要给 dedicated 路径用，例如 transaction、blocking command、pipeline、Pub/Sub。

### 2. `asyncDedicatedConn` 是 LettuceConnectionFactory.getConnection 时创建的吗？

不是。`getConnection()` 创建的是轻量 wrapper，并注入 shared connection 与 provider。`asyncDedicatedConn` 初始为 null，第一次进入 `getOrCreateDedicatedConnection()` 时才创建。

### 3. pipeline 一定提升性能吗？

不一定。pipeline 减少 RTT，但会占用 dedicated connection，增加客户端内存和结果反序列化压力。批次过大或并发过高会降低整体吞吐。

### 4. scan 为什么要关闭 Cursor？

因为 `RedisTemplate.scan(...)` 使用 sticky connection，连接生命周期交给 Cursor。Cursor 不关，wrapper 不 close；复杂配置下会放大为连接泄漏。

### 5. blocking command 能不能放在接口请求里？

不建议。BLPOP/BRPOP/XREAD BLOCK 会占用 dedicated connection，适合独立消费者线程池或独立服务，不适合 C 端主请求链路。

## 生产实践建议

- 普通缓存读写使用 RedisTemplate 正常写，默认共享连接即可。
- 高并发主链路避免 blocking command。
- pipeline 只用于明确批处理，控制批次大小和并发度。
- Redis transaction / WATCH / MULTI / EXEC 只用于确实需要连接级事务语义的场景。
- scan/cursor 必须 try-with-resources。
- 不要在 SessionCallback 中嵌套 scan、pipeline、transaction 等复杂语义。
- 压测时同时看 QPS、RT、连接池 active/idle/wait、Redis command latency、Tomcat 线程状态。
- 记住精确表述：Lettuce 线程安全，非阻塞、非事务的普通命令适合共享连接；连接级状态和阻塞语义必须隔离。

## 本章核心结论

- `asyncSharedConn` 是普通命令优先使用的共享 native connection。
- `asyncDedicatedConn` 是特殊场景按需创建的 wrapper 内部专用连接。
- 当前版本 pipeline 在 `openPipeline()` 时就会触发 dedicated 懒加载。
- transaction / WATCH / MULTI / EXEC 必须固定同一条 dedicated connection。
- scan 的重点是 sticky connection 生命周期，Cursor 不关闭就可能造成资源泄漏。
