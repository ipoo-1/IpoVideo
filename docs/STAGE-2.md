# Stage 2：MySQL + Flyway + 事务

## 1. 本阶段目标

把数据库从教学用的 H2 换成真实项目使用的 MySQL，并用 Flyway 管理表结构版本。
同时掌握事务（Transaction）和索引（Index），做到：

1. 删库重建后，一条启动命令自动恢复全部表结构。
2. 能解释 Flyway 的账本表是怎么工作的。
3. 能讲清楚“改密码 + 旧会话失效”为什么必须放在同一个事务里。

## 2. 先建立一张地图

Stage 2 的关键是看懂“启动时谁在干活”：

```text
Spring Boot 启动
    │
    ▼
连接 MySQL（数据源配置）
    │
    ▼
Flyway 读取 db/migration 下的迁移文件
    │
    ▼
检查账本 flyway_schema_history：V1、V2 跑过没有
    │
    ▼
执行没跑过的迁移：建 users、user_sessions、media_files
    │
    ▼
账本记上执行记录
    │
    ▼
Mapper 就绪，接口开始读写数据
```

记住一句话：**Flyway 是施工队，Mapper 是物业。** 施工队先建表，物业才能在表里存取数据。

## 3. 核心概念

### 3.1 H2 和 MySQL 的区别

H2 是嵌入式内存/文件数据库，零安装，适合学习和测试。
MySQL 是真实项目常用的服务型数据库，支持多用户并发、可靠持久化、成熟运维。

我们的策略：

- 开发/生产：MySQL（`application.properties`）
- 自动化测试：内存 H2（`application-test.properties`），避免测试污染本地数据

这就是配置文件里“profile”存在的意义：同一份代码，不同环境用不同配置。

### 3.2 SQL 四类操作

SQL 分四类，我们的项目都能对上：

| 类型 | 关键字 | 项目场景 |
| :--- | :--- | :--- |
| 查询 | `SELECT` | 登录时按用户名查用户 |
| 新增 | `INSERT` | 注册时插入用户 |
| 修改 | `UPDATE` | 修改密码时更新哈希 |
| 删除 | `DELETE` | 登出时删除会话 |

MyBatis-Plus 的对应写法：

```java
userMapper.selectOne(query)   // SELECT ... WHERE ...
userMapper.insert(user)       // INSERT INTO ...
userMapper.updateById(user)   // UPDATE ... WHERE id = ?
userSessionMapper.delete(q)   // DELETE FROM ... WHERE ...
```

ORM 帮你生成 SQL，但你一定要能看懂 SQL，排错时才不会被框架挡住。

### 3.3 约束：主键、唯一、外键

看 [V1__create_auth_tables.sql](../backend/src/main/resources/db/migration/V1__create_auth_tables.sql)：

```sql
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    ...
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
);
```

- **主键**：每行的身份证，唯一且非空，数据库靠它快速定位。
- **唯一约束**：`username` 不允许重复，是“注册防重”的最终保障，比代码先查一次更可靠。
- **外键**：`user_sessions.user_id` 指向 `users.id`，数据库层面保证“会话一定属于存在的用户”。

### 3.4 索引：为什么查询会慢

没有索引时，`WHERE username = 'coder_01'` 要一行行扫完整张表，叫**全表扫描**。
有索引后，数据库先查“索引目录”定位，像查字典一样。

Stage 2 在 [V2__add_media_files.sql](../backend/src/main/resources/db/migration/V2__add_media_files.sql) 设计了两个组合索引：

```sql
CREATE INDEX idx_media_user_time ON media_files (user_id, upload_time);
CREATE INDEX idx_media_status_time ON media_files (status, upload_time);
```

分别服务两种真实查询：

```sql
-- 前端：我的视频列表，按上传时间倒序
SELECT * FROM media_files WHERE user_id = 1 ORDER BY upload_time DESC;

-- 后台：扫描待处理视频
SELECT * FROM media_files WHERE status = 'UPLOADED' ORDER BY upload_time;
```

索引不是越多越好：每次写入都要同步维护，会变慢、占空间。原则是**为真实查询建索引**。

### 3.5 Flyway：表结构版本的 Git

问题：表结构会一直变。如果手动执行 SQL，谁改的、改到哪一步、漏没漏，全凭记忆，上线必出事故。

Flyway 的做法：每次结构变更写成一个带版本号的 SQL 文件：

```text
db/migration/
  V1__create_auth_tables.sql
  V2__add_media_files.sql
  V3__xxx.sql          <- 以后的新变更
```

命名规则：`V + 数字 + 两个下划线 + 描述`。

启动时 Flyway 查 `flyway_schema_history` 账本，只执行没跑过的版本，执行成功后在账本里记一行。任何环境（开发、测试、生产）跑同一批文件，表结构一定一致。

对照 Git 理解：

| Git | Flyway |
| :--- | :--- |
| 代码版本 | 表结构版本 |
| `commit` | 迁移文件 |
| `git log` | `flyway_schema_history` |
| 旧 commit 不能改 | 旧迁移文件不能改，只能新增 V3 |

**铁律**：已经跑过的迁移文件不能回头改内容。Flyway 会记录文件校验和，改了旧文件它会拒绝执行，防止不同机器把表改成不同样子。

### 3.6 事务：要么全成功，要么全回滚

事务保证一组操作满足 ACID：

| 字母 | 含义 | 通俗解释 |
| :--- | :--- | :--- |
| A | 原子性 | 全部成功或全部失败 |
| C | 一致性 | 数据始终符合规则 |
| I | 隔离性 | 事务之间互不干扰 |
| D | 持久性 | 提交后不丢 |

我们给注册和改密都加了 `@Transactional`：

```java
@Transactional
public void changePassword(Long userId, ChangePasswordRequest request) {
    userMapper.updateById(user);            // 1. 更新密码哈希
    userSessionMapper.delete(sessionQuery); // 2. 让所有旧会话失效
}
```

如果第 2 步失败，第 1 步一起回滚：密码不会“改了但旧会话还在”。
这是产品安全规则“改密码后强制重新登录”的落点，也是事务原子性的真实案例。

### 3.7 为什么建表的不是 Mapper

启动顺序：数据源连接 -> Flyway 建表 -> Spring 创建 Mapper -> 接口读写。

Mapper 只是把 Java 对象翻译成 SQL 的工具，它**假设表已经存在**。所以：

- Flyway：施工队，负责建表。
- Mapper：物业，负责存取数据。

面试被问“谁创建的表”，答“Flyway 通过迁移文件”，不要答“Mapper”。

## 4. 代码地图

| 文件 | 作用 |
| :--- | :--- |
| `db/migration/V1__create_auth_tables.sql` | 建 users / user_sessions |
| `db/migration/V2__add_media_files.sql` | 建 media_files + 两个组合索引 |
| `pom.xml` | 新增 MySQL 驱动、flyway-core、flyway-mysql |
| `application.properties` | 数据源指向 MySQL，启用 Flyway |
| `application-test.properties` | 测试继续用内存 H2 |
| `AuthService.java` | 注册/改密加 `@Transactional`；改密后失效全部会话 |
| `scripts/init-mysql.sql` | 一次性初始化数据库和账号 |

## 5. 一次启动的完整旅程

1. 你执行 `.\mvnw.cmd spring-boot:run`。
2. Spring Boot 启动，读到 `application.properties`，连接 `jdbc:mysql://localhost:3306/dovideo`。
3. Flyway 启动，读取 `db/migration`，查 `flyway_schema_history`。
4. 账本为空，执行 V1 建 `users`、`user_sessions`，执行 V2 建 `media_files` 和索引。
5. 账本写入两行记录（`version=1`、`version=2`，`success=1`）。
6. Spring 创建 Mapper，服务开始监听 9090。
7. 你调用注册接口，`UserMapper.insert(user)` 在 MySQL 里真正插一行数据。

## 6. 运行与验证

### 6.1 连接 HeidiSQL

1. 打开 HeidiSQL，新建会话：主机 `127.0.0.1`，端口 `3306`，用户 `root`，填安装时设置的密码。
2. 执行 [init-mysql.sql](../scripts/init-mysql.sql)，创建数据库 `dovideo` 和账号 `dovideo/dovideo123`。
3. 左侧能看到 `dovideo` 库。

### 6.2 启动后端

```powershell
cd D:\No1\DOVideo-AI-main\rebuild\backend
.\mvnw.cmd spring-boot:run
```

盯日志：看到 `Successfully applied 2 migrations` 后，刷新 HeidiSQL，`dovideo` 库里出现 4 张表：

```text
users
user_sessions
media_files
flyway_schema_history
```

### 6.3 让数据活起来

```powershell
powershell -ExecutionPolicy Bypass -File D:\No1\DOVideo-AI-main\rebuild\scripts\stage1-check.ps1
```

回 HeidiSQL，右键 `users` -> 选择行，刚才注册的用户已经变成一行真实数据。

## 7. 作业

### 动手任务一：写第三个迁移

新建 `V3__add_media_size.sql`：

```sql
ALTER TABLE media_files ADD COLUMN size_bytes BIGINT NULL;
```

重启项目，观察：

- 日志只执行 V3；
- `flyway_schema_history` 多了一行；
- HeidiSQL 里 `media_files` 多了 `size_bytes` 列。

再思考：为什么不能直接改 V1/V2，而要新增 V3？

### 动手任务二：手写 SQL

在 HeidiSQL 的查询窗口（F9 执行）手写：

1. 查询所有用户名：`SELECT username FROM users;`
2. 按用户名查用户：`SELECT * FROM users WHERE username = '你注册的用户名';`
3. 删除某用户全部会话：`DELETE FROM user_sessions WHERE user_id = 1;`

### 思考题

1. 唯一约束和“代码里先查一次”相比，为什么数据库约束更可靠？
2. 为什么 `(user_id, upload_time)` 用组合索引，而不是两个单独索引？
3. `@Transactional` 放在 Service 层而不是 Controller 层，为什么？
4. 改密码时先更新密码再删会话，如果删会话失败且没有事务，会发生什么？

## 8. 面试自测题

1. Flyway 的原理是什么？`flyway_schema_history` 表有什么作用？
2. 什么是事务的原子性？举一个你项目里的例子。
3. 什么情况下 SQL 会全表扫描？索引为什么能加速？
4. 主键、唯一约束、外键分别解决什么问题？
5. 为什么真实项目不用 H2，而用 MySQL？
6. 测试环境为什么可以继续用 H2？

## 9. 这一阶段在简历上怎么说

> 使用 MySQL 8 作为业务数据库，通过 Flyway 版本化迁移管理表结构，
> 实现开发、测试、生产环境结构一致；对注册与改密流程使用 Spring 事务
> 保证原子性（改密同时使全部旧会话失效）；依据查询模式设计组合索引。

等你能独立回答第 8 节全部题目，这句话才算真正属于你。
