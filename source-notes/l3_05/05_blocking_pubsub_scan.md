# L3-05 源码注释：blocking command / pub-sub / scan cursor

## 1. BLPOP / BRPOP

### 文件

`src/main/java/org/springframework/data/redis/connection/lettuce/LettuceListCommands.java`

### 原始源码关键片段

```java
public List<byte[]> bLPop(int timeout, byte[]... keys) {
	Assert.notNull(keys, "Key must not be null!");
	Assert.noNullElements(keys, "Keys must not contain null elements!");

	return connection.invoke(connection.getAsyncDedicatedConnection())
			.from(RedisListAsyncCommands::blpop, timeout, keys)
			.get(LettuceListCommands::toBytesList);
}

public List<byte[]> bRPop(int timeout, byte[]... keys) {
	Assert.notNull(keys, "Key must not be null!");
	Assert.noNullElements(keys, "Keys must not contain null elements!");

	return connection.invoke(connection.getAsyncDedicatedConnection())
			.from(RedisListAsyncCommands::brpop, timeout, keys)
			.get(LettuceListCommands::toBytesList);
}
```

### 我应该打的断点

1. `DefaultListOperations.leftPop(K, long, TimeUnit)` 或 `rightPop(...)`。
2. `LettuceListCommands.bLPop(...)` / `bRPop(...)`。
3. `connection.getAsyncDedicatedConnection()`。
4. `doGetAsyncDedicatedConnection()`。

### 当前方法做了什么

阻塞命令显式传入 `connection.getAsyncDedicatedConnection()`，不走默认 `connection.invoke()`。这是因为 BLPOP/BRPOP 在队列为空时会阻塞等待，timeout=0 时可能无限期占住连接。

### 高并发风险提示

不要在 C 端请求主链路随意用 blocking command。它会占用 dedicated connection；如果连接池小，会导致其他事务/pipeline/阻塞任务等待。更推荐把阻塞消费放到独立消费者线程池/服务里。

## 2. XREAD BLOCK / XREADGROUP BLOCK

### 文件

`src/main/java/org/springframework/data/redis/connection/lettuce/LettuceStreamCommands.java`

### 原始源码关键片段

```java
if (readOptions.isBlocking()) {
	return connection.invoke(getAsyncDedicatedConnection())
			.fromMany(RedisStreamAsyncCommands::xread, args, streamOffsets)
			.toList(StreamConverters.byteRecordConverter());
}

return connection.invoke()
		.fromMany(RedisStreamAsyncCommands::xread, args, streamOffsets)
		.toList(StreamConverters.byteRecordConverter());
```

### 当前方法做了什么

Stream 读不是永远 dedicated。只有 `readOptions.isBlocking()` 时才走 dedicated；非阻塞 XREAD 仍走普通共享分流。

### 高并发风险提示

同一个接口里把 blocking stream read 和普通缓存查询混在一起，会让 dedicated 连接池变成隐藏瓶颈。消费者模型应与用户请求模型隔离。

## 3. Pub/Sub Subscribe

### 文件

`src/main/java/org/springframework/data/redis/connection/lettuce/LettuceConnection.java`

### 原始源码关键片段

```java
public void subscribe(MessageListener listener, byte[]... channels) {
	checkSubscription();

	if (isQueueing() || isPipelined()) {
		throw new UnsupportedOperationException(
				"Transaction/Pipelining is not supported for Pub/Sub subscriptions!");
	}

	try {
		subscription = initSubscription(listener);
		subscription.subscribe(channels);
	} catch (Exception ex) {
		throw convertLettuceAccessException(ex);
	}
}

protected StatefulRedisPubSubConnection<byte[], byte[]> switchToPubSub() {
	checkSubscription();
	reset();
	return connectionProvider.getConnection(StatefulRedisPubSubConnection.class);
}
```

### 当前方法做了什么

Pub/Sub 会把 Redis 连接切到订阅推送模式，这条连接不再适合普通请求响应命令。因此它使用 `StatefulRedisPubSubConnection`，由 `LettuceSubscription` 管理生命周期，不复用 `asyncDedicatedConn` 字段。

### 高并发风险提示

不要把订阅连接和普通 RedisTemplate 命令混用。订阅适合独立 listener container，不适合在 C 端同步请求里临时 subscribe。

## 4. Scan / Cursor

### 文件

`src/main/java/org/springframework/data/redis/core/RedisTemplate.java`

```java
public Cursor<K> scan(ScanOptions options) {
	Assert.notNull(options, "ScanOptions must not be null!");

	return executeWithStickyConnection(
			(RedisCallback<Cursor<K>>) connection -> new ConvertingCursor<>(
					connection.scan(options), this::deserializeKey));
}
```

### 文件

`src/main/java/org/springframework/data/redis/connection/lettuce/LettuceKeyCommands.java`

```java
private Cursor<byte[]> doScan(ScanOptions options) {
	return new LettuceScanCursor<byte[]>(options) {
		@Override
		protected LettuceScanIteration<byte[]> doScan(ScanCursor cursor, ScanOptions options) {
			if (connection.isQueueing() || connection.isPipelined()) {
				throw new UnsupportedOperationException(
						"'SCAN' cannot be called in pipeline / transaction mode.");
			}

			ScanArgs scanArgs = LettuceConverters.toScanArgs(options);
			KeyScanCursor<byte[]> keyScanCursor =
					connection.invoke().just(RedisKeyAsyncCommands::scan, cursor, scanArgs);
			List<byte[]> keys = keyScanCursor.getKeys();

			return new LettuceScanIteration<>(keyScanCursor, keys);
		}

		@Override
		protected void doClose() {
			LettuceKeyCommands.this.connection.close();
		}
	}.open();
}
```

### 当前方法做了什么

`RedisTemplate.scan(...)` 用 sticky connection 返回 Cursor。Cursor 关闭时，才调用底层 connection close。`LettuceKeyCommands.doScan(...)` 还明确禁止在 pipeline / transaction 中执行 SCAN。

### 和 asyncDedicatedConn 的关系

SCAN 本身不是 dedicated 的典型触发点；它走 `connection.invoke()`，普通条件下仍可能使用 shared connection。但 sticky connection 延长了 wrapper 生命周期，必须由 Cursor close 释放。

### 高并发风险提示

游标泄漏经常比普通连接泄漏更隐蔽，因为代码看起来只是“返回了一个 Iterator”。任何 `Cursor` 都要 try-with-resources。
