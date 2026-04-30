# L3-05 源码注释：RedisTemplate.execute / executePipelined / executeWithStickyConnection

## 版本确认

- Spring Data Redis: `2.7.18`
- Spring Framework: `5.3.31`
- Lettuce: `6.1.10.RELEASE`
- 当前源码文件：`src/main/java/org/springframework/data/redis/core/RedisTemplate.java`

## 1. RedisTemplate.execute(...)

### 原始源码关键片段

```java
public <T> T execute(RedisCallback<T> action, boolean exposeConnection, boolean pipeline) {

	Assert.isTrue(initialized, "template not initialized; call afterPropertiesSet() before using it");
	Assert.notNull(action, "Callback object must not be null");

	RedisConnectionFactory factory = getRequiredConnectionFactory();
	RedisConnection conn = RedisConnectionUtils.getConnection(factory, enableTransactionSupport);

	try {
		boolean existingConnection = TransactionSynchronizationManager.hasResource(factory);
		RedisConnection connToUse = preProcessConnection(conn, existingConnection);

		boolean pipelineStatus = connToUse.isPipelined();
		if (pipeline && !pipelineStatus) {
			connToUse.openPipeline();
		}

		RedisConnection connToExpose = (exposeConnection ? connToUse : createRedisConnectionProxy(connToUse));
		T result = action.doInRedis(connToExpose);

		if (pipeline && !pipelineStatus) {
			connToUse.closePipeline();
		}

		return postProcessResult(result, connToUse, existingConnection);
	} finally {
		RedisConnectionUtils.releaseConnection(conn, factory, enableTransactionSupport);
	}
}
```

### 我应该打的断点

1. `RedisTemplate.execute(RedisCallback, boolean, boolean)` 第 1 行。
2. `RedisConnectionUtils.getConnection(factory, enableTransactionSupport)`。
3. `connToUse.openPipeline()`。
4. `action.doInRedis(connToExpose)`。
5. `RedisConnectionUtils.releaseConnection(...)`。

### 当前方法做了什么

`RedisTemplate.execute` 是 Template Method：它固定“拿连接 -> 执行业务 callback -> 释放连接”的骨架，把真正 Redis 命令留给 callback。普通 `opsForValue().get(...)`、`opsForValue().set(...)` 最终都会进入这个模板入口。

### 和 asyncDedicatedConn 的关系

这个方法本身不直接知道 `asyncSharedConn` 和 `asyncDedicatedConn`。它只拿到 Spring Data Redis 抽象的 `RedisConnection`。真正分流发生在 `LettuceConnection.getAsyncConnection()`：

- 普通命令：`isQueueing=false` 且 `isPipelined=false`，优先返回 `asyncSharedConn.async()`。
- pipeline：这里调用 `connToUse.openPipeline()`，在当前 2.7.18 版本中会触发 `LettuceConnection.getOrCreateDedicatedConnection()`，从而懒加载 `asyncDedicatedConn`。

### 高并发风险提示

`execute(...)` 的 finally 是普通路径释放连接的兜底。如果你绕开 RedisTemplate 手动拿 `RedisConnection`，或者 Cursor 没有关闭，`releaseConnection` 可能不会按预期执行。高并发下表现为连接池 active 不下降、borrow 等待升高、Tomcat 线程堆积。

## 2. RedisTemplate.executePipelined(...)

### 原始源码关键片段

```java
public List<Object> executePipelined(RedisCallback<?> action, RedisSerializer<?> resultSerializer) {

	return execute((RedisCallback<List<Object>>) connection -> {
		connection.openPipeline();
		boolean pipelinedClosed = false;
		try {
			Object result = action.doInRedis(connection);
			if (result != null) {
				throw new InvalidDataAccessApiUsageException(
						"Callback cannot return a non-null value as it gets overwritten by the pipeline");
			}
			List<Object> closePipeline = connection.closePipeline();
			pipelinedClosed = true;
			return deserializeMixedResults(closePipeline, resultSerializer, hashKeySerializer, hashValueSerializer);
		} finally {
			if (!pipelinedClosed) {
				connection.closePipeline();
			}
		}
	});
}
```

### 我应该打的断点

1. `RedisTemplate.executePipelined(...)`。
2. `connection.openPipeline()`。
3. `LettuceConnection.openPipeline()`。
4. `LettuceConnection.getOrCreateDedicatedConnection()`。
5. `LettuceConnection.doGetAsyncDedicatedConnection()`。

### 当前方法做了什么

它用 `execute(...)` 包住 pipeline 生命周期，先打开 pipeline，让 callback 中的命令进入同一批次，最后 `closePipeline()` 等待结果并反序列化。

### 和 asyncDedicatedConn 的关系

当前版本 `LettuceConnection.openPipeline()` 不是单纯设置 `isPipelined=true`。它还会执行：

```java
flushState = this.pipeliningFlushPolicy.newPipeline();
flushState.onOpen(this.getOrCreateDedicatedConnection());
```

这意味着 pipeline 打开时就会创建或复用当前 wrapper 的 `asyncDedicatedConn`。

### 高并发风险提示

pipeline 是“同一连接上批量排队”，不是免费性能按钮。批量太大时会占用 dedicated connection、增大客户端内存、增大响应体反序列化压力。池化配置下，大量并发 pipeline 会直接竞争 pool 的 `maxTotal`。

## 3. RedisTemplate.executeWithStickyConnection(...)

### 原始源码关键片段

```java
public <T extends Closeable> T executeWithStickyConnection(RedisCallback<T> callback) {

	Assert.isTrue(initialized, "template not initialized; call afterPropertiesSet() before using it");
	Assert.notNull(callback, "Callback object must not be null");

	RedisConnectionFactory factory = getRequiredConnectionFactory();

	RedisConnection connection = preProcessConnection(
			RedisConnectionUtils.doGetConnection(factory, true, false, false), false);

	return callback.doInRedis(connection);
}
```

### 我应该打的断点

1. `RedisTemplate.scan(ScanOptions)`。
2. `RedisTemplate.executeWithStickyConnection(...)`。
3. `RedisConnectionUtils.doGetConnection(factory, true, false, false)`。
4. `LettuceKeyCommands.doScan(...)`。
5. `LettuceKeyCommands` 匿名 cursor 的 `doClose()`。

### 当前方法做了什么

它拿连接但不在 finally 中释放，因为返回值是 `Closeable`，典型就是 `Cursor`。Cursor 后续迭代还要继续发 SCAN，因此连接必须粘在 Cursor 生命周期上。

### 和 asyncDedicatedConn 的关系

SCAN 不等于必然创建 `asyncDedicatedConn`。当前版本 `LettuceKeyCommands.doScan(...)` 内部使用 `connection.invoke()`，普通 shared 条件下仍可能走 `asyncSharedConn`。本章要记住：SCAN 的风险重点是 sticky connection 的生命周期，不是“所有 scan 都走 dedicated”。

### 高并发风险提示

Cursor 必须关闭。没有关闭时，RedisConnection wrapper 不会关闭；在 `shareNativeConnection=false`、池化 dedicated、复杂嵌套语义下，会放大为连接泄漏或池耗尽。
