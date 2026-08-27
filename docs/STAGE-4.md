# Stage 4：Redis（缓存、会话、限流、分布式锁）

## 1. 本阶段目标

引入 Redis，解决三个真实产品问题：

1. 登录会话从数据库搬到 Redis：读写更快，服务重启也不丢登录状态。
2. 登录失败限流：同一账号连续输错密码，触发 429，防暴力破解。
3. 任务防重：同一视频重复提交分析任务，用分布式锁拦截，不重复烧钱。

贯穿全阶段的设计原则：

> MySQL 是业务真相，Redis 是加速器。可以丢 Redis，不能丢 MySQL。

## 2. 先建立一张地图

```text
浏览器
  │
  ▼
Controller -> Service
  │              │
  │              ├── Redis（内存，快，可丢）
  │              │      会话、限流计数、防重锁
  │              │
  │              ▼
  │          MySQL（磁盘，慢，不可丢）
  │              任务状态、用户、视频元数据
  ▼
响应
```

什么数据放哪里，取决于一个判断题：**丢了这个数据，用户能不能接受？**

| 数据 | 放哪 | 原因 |
| :--- | :--- | :--- |
| 登录会话 | Redis | 丢了最多重新登录，可接受 |
| 限流计数 | Redis | 丢了最多多试几次，可接受 |
| 防重锁 | Redis | 丢了最多重复提交一次，可接受 |
| 任务最终状态 | MySQL | 丢了用户就不知道结果了，不可接受 |
| 用户账号 | MySQL | 丢了整个产品就完了，不可接受 |

## 3. 核心概念

### 3.1 Redis 是什么

Redis 是一个**基于内存的键值数据库**。数据存在内存里，所以读写是微秒级；
它支持多种数据结构，我们这阶段用到两种：

| 结构 | 类比 | 我们的用途 |
| :--- | :--- | :--- |
| String | 一个键对应一个值 | `auth:session:{token} -> userId` |
| Set | 一堆不重复的值 | `auth:user:sessions:{userId}` 记录用户所有 token |

### 3.2 TTL：让数据自动过期

Redis 的每个 key 都可以设置过期时间（TTL），到点自动删除。
这正好匹配“会话 24 小时过期”“限流计数 10 分钟清零”的需求，
不用我们自己写定时任务去清数据。

### 3.3 Key 命名规范

用“业务域:对象:标识”的方式命名，避免不同功能互相撞 key：

```text
auth:session:{token}          登录会话
auth:user:sessions:{userId}   某用户的所有 token
auth:login-failures:{username} 登录失败次数
task:lock:{mediaId}           任务防重锁
```

### 3.4 会话为什么从 MySQL 搬到 Redis

原来登录会话存在 `user_sessions` 表。问题：

- 每次请求都要查一次数据库（慢）
- 表越来越大，全是过期垃圾行
- 服务有多台时，数据库成为瓶颈

搬到 Redis 后：

- 查会话变成一次内存读取，微秒级
- key 自带 TTL，24 小时后自动消失，不用清理
- 多台后端实例共享同一个 Redis，登录状态不丢失

### 3.5 登录限流：INCR + EXPIRE

用户输错密码时：

```text
INCR auth:login-failures:coder_01   -> 失败次数 +1
EXPIRE ... 600                      -> 10 分钟后自动清零
```

达到 8 次后返回 429。登录成功时把这个 key 删掉，次数清零。

### 3.6 分布式锁：SETNX + TTL + 所有权校验

单机时代防重可以用 Java 的 `synchronized`，但它只在**一台机器**内有效。
多台后端时，两个请求可能落在两台机器上，`synchronized` 管不住。

Redis 分布式锁的思路：

```text
SETNX task:lock:5 "机器A" EX 5
  成功 -> 拿到锁，只有我能继续
  失败 -> 别人正在处理，返回 409
做完 -> 校验 value 还是我，再删除锁
```

两个关键细节：

- 锁必须带 TTL：防止持有锁的进程崩溃，锁永远不释放。
- 删除前要校验所有权：如果锁已过期被别人拿走，不能误删别人的锁。

## 4. 代码地图（本阶段新增/改动）

| 文件 | 作用 |
| :--- | :--- |
| `pom.xml` | 新增 spring-boot-starter-data-redis |
| `application.properties` | Redis 连接配置 |
| `config/RedisKeys.java` | key 命名统一管理 |
| `service/AuthService.java` | 会话改存 Redis；登录限流 |
| `service/TaskService.java` | 创建任务前加 Redis 分布式锁 |
| `AuthFlowIntegrationTest.java` | 限流、会话测试 |
| `TaskFlowIntegrationTest.java` | 防重测试 |

## 5. 一次登录的完整旅程（Redis 版）

1. 用户提交用户名密码。
2. `AuthService` 从 MySQL 查用户，校验密码哈希。
3. 失败：`INCR auth:login-failures:{username}`，超过 8 次返回 429。
4. 成功：生成 token，写入 `auth:session:{token} -> userId`，TTL 24 小时。
5. 同时把 token 加入 `auth:user:sessions:{userId}` 这个 Set，便于改密时全部失效。
6. 后续请求带 token，拦截器从 Redis 读 userId，不再查数据库。

## 6. 运行与验证

### 6.1 启动 Redis

新开一个终端：

```powershell
D:\Redis\Redis-x64-5.0.14.1\redis-server.exe
```

看到 `Ready to accept connections` 就是启动成功（保持窗口开着）。

验证：

```powershell
D:\Redis\Redis-x64-5.0.14.1\redis-cli.exe ping
```

返回 `PONG` 就正常。

### 6.2 启动后端

```powershell
cd D:\No1\DOVideo-AI-main\rebuild\backend
.\mvnw.cmd spring-boot:run
```

### 6.3 观察 Redis 里的数据

登录成功后，在 redis-cli 里执行：

```powershell
keys auth:*
get auth:session:<token>
ttl auth:session:<token>
```

连错 8 次密码，再登录应该返回 429。

## 7. 作业

### 动手任务

用 redis-cli 亲手做一遍这些命令，理解它们的语义：

```text
SET task:lock:1 "me" EX 5
SETNX task:lock:1 "other"      -> 返回 0，说明锁被占
GET task:lock:1
TTL task:lock:1
等待 5 秒后 GET task:lock:1    -> 自动消失

INCR auth:login-failures:coder_01
INCR auth:login-failures:coder_01
TTL auth:login-failures:coder_01
```

### 思考题

1. 为什么锁必须带 TTL？没有 TTL 会发生什么？
2. 删除锁之前为什么要校验 value 是不是自己的？
3. Redis 里的会话丢了会怎样？MySQL 里的任务状态丢了会怎样？
4. `synchronized` 为什么不能替代 Redis 分布式锁？

## 8. 面试自测题

1. Redis 为什么快？它和 MySQL 的本质区别是什么？
2. TTL 解决了什么问题？举例说明。
3. 登录会话为什么适合放 Redis，不适合放 MySQL？
4. INCR + EXPIRE 怎么实现限流？
5. Redis 分布式锁的原理是什么？有什么坑？
6. “Redis 可以丢，MySQL 不能丢”这句话怎么理解？

## 9. 这一阶段在简历上怎么说

> 引入 Redis 重构认证与任务链路：登录会话迁移至 Redis（String + Set + TTL），
> 支持多实例共享且服务重启不丢登录；使用 INCR + EXPIRE 实现登录失败限流；
> 通过带 TTL 与所有权校验的 Redis 分布式锁防止视频任务重复提交。

等你能独立回答第 8 节全部题目，这句话才算真正属于你。
