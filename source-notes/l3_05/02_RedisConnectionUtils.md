# L3-05 源码注释：RedisConnectionUtils

## 文件

`src/main/java/org/springframework/data/redis/core/RedisConnectionUtils.java`

## 1. getConnection / doGetConnection

### 原始源码关键片段

```java
public static RedisConnection getConnection(RedisConnectionFactory factory, boolean transactionSupport) {
	return doGetConnection(factory, true, false, transactionSupport);
}

public static RedisConnection doGetConnection(RedisConnectionFactory factory, boolean allowCreate, boolean bind,
		boolean transactionSupport) {

	RedisConnectionHolder conHolder =
			(RedisConnectionHolder) TransactionSynchronizationManager.getResource(factory);

	if (conHolder != null && (conHolder.hasConnection() || conHolder.isSynchronizedWithTransaction())) {
		conHolder.requested();
		if (!conHolder.hasConnection()) {
			conHolder.setConnection(fetchConnection(factory));
		}
		return conHolder.getRequiredConnection();
	}

	if (!allowCreate) {
		throw new IllegalArgumentException("No connection found and allowCreate = false");
	}

	RedisConnection connection = fetchConnection(factory);
	boolean bindSynchronization =
			TransactionSynchronizationManager.isActualTransactionActive() && transactionSupport;

	if (bind || bindSynchronization) {
		RedisConnectionHolder holderToUse = conHolder;
		if (holderToUse == null) {
			holderToUse = new RedisConnectionHolder(connection);
		} else {
			holderToUse.setConnection(connection);
		}
		holderToUse.requested();
		if (holderToUse != conHolder) {
			TransactionSynchronizationManager.bindResource(factory, holderToUse);
		}
		return connection;
	}

	return connection;
}
```

### 我应该打的断点

1. `TransactionSynchronizationManager.getResource(factory)`。
2. `fetchConnection(factory)`。
3. `new RedisConnectionHolder(connection)`。
4. `TransactionSynchronizationManager.bindResource(factory, holderToUse)`。

### 当前方法做了什么

它是 Spring 资源管理层，负责决定“当前线程是否已经有 RedisConnection wrapper”。如果有 holder，就复用 holder 里的连接；如果没有，就从 factory 获取新 wrapper。

### 和 asyncDedicatedConn 的关系

`asyncDedicatedConn` 是 `LettuceConnection` wrapper 内部字段。事务和 `SessionCallback` 需要复用同一个 wrapper，原因是：

- `MULTI` 后 `isMulti=true` 存在 wrapper 上；
- `asyncDedicatedConn` 懒加载后也存在同一个 wrapper 上；
- 如果每次 template 调用都换 wrapper，事务队列和 dedicated connection 都会断裂。

### 高并发风险提示

ThreadLocal holder 是强工具，也有强约束。异常路径必须 unbind，否则请求线程复用时可能拿到上一个请求残留的资源。Spring 用 try/finally 和同步回调兜底，业务自己写 ThreadLocal 资源管理时也要照抄这种纪律。

## 2. releaseConnection / unbindConnection

### 原始源码关键片段

```java
public static void releaseConnection(RedisConnection conn, RedisConnectionFactory factory) {
	if (conn == null) {
		return;
	}

	RedisConnectionHolder conHolder =
			(RedisConnectionHolder) TransactionSynchronizationManager.getResource(factory);
	if (conHolder != null) {
		if (conHolder.isTransactionActive()) {
			if (connectionEquals(conHolder, conn)) {
				conHolder.released();
			}
			return;
		}

		unbindConnection(factory);
		return;
	}

	doCloseConnection(conn);
}

public static void unbindConnection(RedisConnectionFactory factory) {
	RedisConnectionHolder conHolder =
			(RedisConnectionHolder) TransactionSynchronizationManager.getResource(factory);
	if (conHolder == null) {
		return;
	}

	if (conHolder.isTransactionActive()) {
		// close after transaction finished
	} else {
		RedisConnection connection = conHolder.getConnection();
		conHolder.released();
		if (!conHolder.isOpen()) {
			TransactionSynchronizationManager.unbindResourceIfPossible(factory);
			doCloseConnection(connection);
		}
	}
}
```

### 我应该打的断点

1. `RedisConnectionUtils.releaseConnection(...)`。
2. `conHolder.isTransactionActive()`。
3. `RedisConnectionUtils.unbindConnection(...)`。
4. `doCloseConnection(connection)`。
5. `LettuceConnection.close()`。

### 当前方法做了什么

它决定连接是现在关闭，还是因为事务/thread-bound holder 推迟关闭。普通 `RedisTemplate.execute(...)` 没有 holder 时，会直接 close wrapper；SessionCallback 会在 unbind 后关闭。

### 和 asyncDedicatedConn 的关系

`LettuceConnection.close()` 会进入 `reset()`。如果 `asyncDedicatedConn != null`，就调用 `connectionProvider.release(asyncDedicatedConn)`。因此 release 链路是否执行，是 dedicated connection 是否归还连接池的关键。

### 高并发风险提示

连接池 active 不下降时，不要只看 Redis。要顺着这条链路查：

```text
RedisTemplate finally
  -> RedisConnectionUtils.releaseConnection
  -> doCloseConnection
  -> LettuceConnection.close
  -> reset
  -> connectionProvider.release(asyncDedicatedConn)
```

任何一个环节没走到，都可能造成连接池耗尽。
