# L3-05 源码注释：LettuceConnectionFactory

## 文件

`src/main/java/org/springframework/data/redis/connection/lettuce/LettuceConnectionFactory.java`

## 1. 类级别注释里的关键事实

### 原始源码关键片段

```java
/**
 * This factory creates a new LettuceConnection on each call to getConnection().
 * Multiple LettuceConnections share a single thread-safe native connection by default.
 *
 * The shared native connection is never closed by LettuceConnection ...
 * Inject a Pool to pool dedicated connections. If shareNativeConnection is true,
 * the pool will be used to select a connection for blocking and tx operations only,
 * which should not share a connection.
 */
public class LettuceConnectionFactory implements InitializingBean, DisposableBean,
		RedisConnectionFactory, ReactiveRedisConnectionFactory {

	private boolean shareNativeConnection = true;
	private @Nullable SharedConnection<byte[]> connection;
	private @Nullable LettuceConnectionProvider connectionProvider;
}
```

### 当前方法做了什么

类注释已经把本章答案写得很直白：

- 每次 `getConnection()` 都创建新的 `LettuceConnection` wrapper；
- 多个 wrapper 默认共享一个线程安全 native connection；
- dedicated connection 可以池化；
- `shareNativeConnection=true` 时，pool 主要用于 blocking 和 tx 等不能共享连接的操作。

### 高并发风险提示

很多新手以为“每次 getConnection 都 new LettuceConnection，所以每次都新建 TCP”。这是错的。new 的是 wrapper，真正 native connection 默认共享。真正会打到连接池或新建连接的是 dedicated 路径。

## 2. getConnection()

### 原始源码关键片段

```java
public RedisConnection getConnection() {

	assertInitialized();

	if (isClusterAware()) {
		return getClusterConnection();
	}

	LettuceConnection connection;
	connection = doCreateLettuceConnection(getSharedConnection(), connectionProvider, getTimeout(), getDatabase());
	connection.setConvertPipelineAndTxResults(convertPipelineAndTxResults);
	return connection;
}
```

### 我应该打的断点

1. `assertInitialized()`。
2. `getSharedConnection()`。
3. `doCreateLettuceConnection(...)`。
4. `LettuceConnection` 构造方法。

### 和 asyncDedicatedConn 的关系

`getConnection()` 同时把两个重要东西塞进 wrapper：

- `getSharedConnection()`：可能返回共享 native connection，进入 `asyncSharedConn` 字段；
- `connectionProvider`：以后 dedicated 路径需要借连接时，从它这里拿。

此时 `asyncDedicatedConn` 还没有创建。它是 lazy 的。

## 3. getSharedConnection()

### 原始源码关键片段

```java
@Nullable
protected StatefulRedisConnection<byte[], byte[]> getSharedConnection() {
	return shareNativeConnection && !isClusterAware()
			? (StatefulRedisConnection) getOrCreateSharedConnection().getConnection()
			: null;
}
```

### 我应该打的断点

1. `shareNativeConnection && !isClusterAware()`。
2. `getOrCreateSharedConnection().getConnection()`。
3. 内部类 `SharedConnection.getConnection()`。

### 和 asyncDedicatedConn 的关系

如果这里返回非 null，普通命令优先使用 `asyncSharedConn`。如果这里返回 null，例如 `shareNativeConnection=false`，那么普通命令走到 `LettuceConnection.getAsyncConnection()` 时也会退到 `getAsyncDedicatedConnection()`。

## 4. createConnectionProvider(...)

### 原始源码关键片段

```java
private LettuceConnectionProvider createConnectionProvider(AbstractRedisClient client, RedisCodec<?, ?> codec) {
	if (this.pool != null) {
		return new LettucePoolConnectionProvider(this.pool);
	}

	LettuceConnectionProvider connectionProvider = doCreateConnectionProvider(client, codec);

	if (this.clientConfiguration instanceof LettucePoolingClientConfiguration) {
		return new LettucePoolingConnectionProvider(connectionProvider,
				(LettucePoolingClientConfiguration) this.clientConfiguration);
	}

	return connectionProvider;
}
```

### 我应该打的断点

1. `this.clientConfiguration instanceof LettucePoolingClientConfiguration`。
2. `new LettucePoolingConnectionProvider(...)`。
3. `LettucePoolingConnectionProvider.getConnection(Class)`。

### 和 asyncDedicatedConn 的关系

`asyncDedicatedConn` 的真实来源不是 `new LettuceConnection(...)`，而是后续：

```text
LettuceConnection.doGetAsyncDedicatedConnection
  -> connectionProvider.getConnection(StatefulConnection.class)
```

如果 provider 是 pooling provider，这一步就是从连接池 borrow；否则就是直接创建/获取一条 native connection。
