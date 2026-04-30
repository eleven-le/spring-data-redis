# L3-05 源码注释：LettuceConnection shared/dedicated 分流

## 文件

`src/main/java/org/springframework/data/redis/connection/lettuce/LettuceConnection.java`

## 1. 关键字段

### 原始源码关键片段

```java
private final LettuceConnectionProvider connectionProvider;
private final @Nullable StatefulConnection<byte[], byte[]> asyncSharedConn;
private @Nullable StatefulConnection<byte[], byte[]> asyncDedicatedConn;

private boolean isMulti = false;
private boolean isPipelined = false;
```

### 当前字段做了什么

- `asyncSharedConn`：构造时注入，普通命令优先复用。
- `asyncDedicatedConn`：初始为 null，特殊场景第一次用到时懒加载。
- `connectionProvider`：创建/借出/释放 dedicated connection 的策略入口。
- `isMulti`：事务排队状态。
- `isPipelined`：pipeline 状态。

### 高并发风险提示

不要只背“Lettuce 线程安全”。更精确的说法是：非阻塞、非事务、普通请求响应命令适合共享连接；连接级状态命令必须隔离。

## 2. getAsyncConnection()

### 原始源码关键片段

```java
RedisClusterAsyncCommands<byte[], byte[]> getAsyncConnection() {

	if (isQueueing() || isPipelined()) {
		return getAsyncDedicatedConnection();
	}

	if (asyncSharedConn != null) {
		if (asyncSharedConn instanceof StatefulRedisConnection) {
			return ((StatefulRedisConnection<byte[], byte[]>) asyncSharedConn).async();
		}
		if (asyncSharedConn instanceof StatefulRedisClusterConnection) {
			return ((StatefulRedisClusterConnection<byte[], byte[]>) asyncSharedConn).async();
		}
	}
	return getAsyncDedicatedConnection();
}
```

### 我应该打的断点

1. `isQueueing() || isPipelined()`。
2. `asyncSharedConn != null`。
3. `return getAsyncDedicatedConnection()`。

### 当前方法做了什么

它是普通命令的 shared/dedicated 分流口。绝大多数 Redis 命令适配类最终通过 `connection.invoke()` 进入这里。

### 和 asyncDedicatedConn 的关系

触发 dedicated 的条件：

- `isQueueing()==true`：事务中；
- `isPipelined()==true`：pipeline 中；
- `asyncSharedConn==null`：比如 `shareNativeConnection=false`。

## 3. openPipeline()

### 原始源码关键片段

```java
public void openPipeline() {
	if (!isPipelined) {
		isPipelined = true;
		ppline = new ArrayList<>();
		flushState = this.pipeliningFlushPolicy.newPipeline();
		flushState.onOpen(this.getOrCreateDedicatedConnection());
	}
}
```

### 我应该打的断点

1. `isPipelined = true`。
2. `this.getOrCreateDedicatedConnection()`。
3. `doGetAsyncDedicatedConnection()`。

### 当前方法做了什么

当前 2.7.18 版本打开 pipeline 时就会触发 dedicated connection 懒加载。这一点非常关键：不是等第一条 pipeline 命令执行时才创建，而是 `openPipeline()` 已经会创建。

### 高并发风险提示

大量并发 `executePipelined` 会立即占用 dedicated connection。连接池过小会 borrow wait；pipeline 批次过大会导致单连接被长时间占用。

## 4. multi / exec / watch

### 原始源码关键片段

```java
public void multi() {
	if (isQueueing()) {
		return;
	}
	isMulti = true;
	try {
		if (isPipelined()) {
			getAsyncDedicatedRedisCommands().multi();
			return;
		}
		getDedicatedRedisCommands().multi();
	} catch (Exception ex) {
		throw convertLettuceAccessException(ex);
	}
}

public List<Object> exec() {
	isMulti = false;
	try {
		if (isPipelined()) {
			RedisFuture<TransactionResult> exec = getAsyncDedicatedRedisCommands().exec();
			...
			return null;
		}
		TransactionResult transactionResult = getDedicatedRedisCommands().exec();
		...
	} finally {
		txResults.clear();
	}
}
```

### 当前方法做了什么

`multi()` 先把 wrapper 标成 queueing 状态，再通过 dedicated commands 发送 MULTI。`exec()` 在同一个 dedicated connection 上执行 EXEC，并清理事务结果队列。

### 高并发风险提示

如果事务走共享连接，别的线程的普通命令可能被错误塞进 MULTI 队列。如果事务中途换连接，WATCH/MULTI/EXEC 的连接级语义断裂。

## 5. getAsyncDedicatedConnection / doGetAsyncDedicatedConnection / getOrCreateDedicatedConnection

### 原始源码关键片段

```java
protected RedisClusterAsyncCommands<byte[], byte[]> getAsyncDedicatedConnection() {
	if (isClosed()) {
		throw new RedisSystemException("Connection is closed", null);
	}

	StatefulConnection<byte[], byte[]> connection = getOrCreateDedicatedConnection();

	if (connection instanceof StatefulRedisConnection) {
		return ((StatefulRedisConnection<byte[], byte[]>) connection).async();
	}
	if (asyncDedicatedConn instanceof StatefulRedisClusterConnection) {
		return ((StatefulRedisClusterConnection<byte[], byte[]>) connection).async();
	}

	throw new IllegalStateException(...);
}

protected StatefulConnection<byte[], byte[]> doGetAsyncDedicatedConnection() {
	StatefulConnection connection = connectionProvider.getConnection(StatefulConnection.class);
	if (customizedDatabaseIndex()) {
		potentiallySelectDatabase(dbIndex);
	}
	return connection;
}

private StatefulConnection<byte[], byte[]> getOrCreateDedicatedConnection() {
	if (asyncDedicatedConn == null) {
		asyncDedicatedConn = doGetAsyncDedicatedConnection();
	}
	return asyncDedicatedConn;
}
```

### 我应该打的断点

1. `getAsyncDedicatedConnection()` 方法入口。
2. `getOrCreateDedicatedConnection()` 中 `asyncDedicatedConn == null`。
3. `connectionProvider.getConnection(StatefulConnection.class)`。
4. `LettucePoolingConnectionProvider.getConnection(Class)`。

### 当前方法做了什么

这是 `asyncDedicatedConn` 的真正创建时机：不是构造 `LettuceConnection` 时，而是第一次特殊场景需要专用连接时。

### 高并发风险提示

连接池耗尽通常发生在这里背后的 provider borrow 阶段。问题表面可能是接口 RT 抖动，根因却是业务把 pipeline、事务、阻塞命令放进高并发主链路，导致 dedicated 连接被大量占用。

## 6. close / reset

### 原始源码关键片段

```java
public void close() {
	super.close();
	if (isClosed) {
		return;
	}
	isClosed = true;
	try {
		reset();
	} catch (RuntimeException e) {
		LOGGER.debug("Failed to reset connection during close", e);
	}
}

private void reset() {
	if (asyncDedicatedConn != null) {
		try {
			if (customizedDatabaseIndex()) {
				potentiallySelectDatabase(defaultDbIndex);
			}
			connectionProvider.release(asyncDedicatedConn);
			asyncDedicatedConn = null;
		} catch (RuntimeException ex) {
			throw convertLettuceAccessException(ex);
		}
	}
	...
}
```

### 我应该打的断点

1. `LettuceConnection.close()`。
2. `reset()` 中 `asyncDedicatedConn != null`。
3. `connectionProvider.release(asyncDedicatedConn)`。

### 当前方法做了什么

关闭的是 wrapper。shared native connection 不会被普通 wrapper close 掉；dedicated connection 会在 wrapper close 时释放给 provider。

### 高并发风险提示

只要 dedicated connection 被创建，就必须保证 wrapper 关闭。Cursor 不关、手动拿连接不关、异常路径吞掉 finally，都会把 dedicated 资源拖住。
