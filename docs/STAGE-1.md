# Stage 1：MVP 主链路（注册 / 登录）

## 1. 本阶段目标

把"用户注册 -> 登录 -> 访问自己的信息"这条主链路端到端跑通。
完成之后，你会理解一个后端请求从浏览器到数据库、再返回浏览器的完整旅程。

## 2. 先建立一张地图

```text
浏览器/Postman
    |
    | HTTP 请求（POST /api/auth/register, JSON 数据）
    v
Spring Boot（Tomcat 服务器）
    |
    | 1. 全局异常处理器兜底
    | 2. 拦截器先检查登录（注册/登录接口除外）
    | 3. @Valid 校验参数
    v
Controller 层   -> 只负责接收请求、返回响应
    |
    v
Service 层      -> 写业务规则（密码哈希、查重、建会话）
    |
    v
Mapper 层       -> 用 MyBatis-Plus 把对象翻译成 SQL
    |
    v
H2 数据库       -> 真正存数据
```

这是后端最经典的分层：**各层只做一件事，上层依赖下层**。
面试时能画出这张图并讲清每层职责，比背代码更值钱。

## 3. 核心概念

### 3.1 HTTP 与 REST

HTTP 是浏览器和服务器之间的"快递协议"。一次请求由三部分组成：

- 方法：GET（取数据）、POST（提交数据）、PUT（整体更新）、DELETE（删除）
- 路径：`/api/auth/register` 表示"注册"这个资源动作
- 请求体：通常是 JSON，比如 `{"username":"coder_01","password":"secret123"}`

我们遵循 REST 风格：用名词表示资源，用 HTTP 方法表示动作。
例如 `/api/me` 是"当前用户"这个资源，`GET` 表示获取它。

### 3.2 HTTP 状态码

- 200：成功
- 400：参数错误
- 401：未登录 / 认证失败
- 409：冲突（例如用户名已存在）
- 500：服务器内部错误

### 3.3 统一响应体 Result

所有接口都返回同一个结构：`{"code": 0, "message": "success", "data": ...}`。

好处：前端只需写一套解析逻辑；错误信息统一携带；日志排查方便。
这是很多真实项目的约定，也是参考实现里的设计，我们继续沿用。

### 3.4 Controller / Service / Mapper

| 层 | 职责 | 类比 |
| :--- | :--- | :--- |
| Controller | 接收请求、调服务、返回响应 | 前台接待 |
| Service | 业务规则、事务边界 | 业务经理 |
| Mapper | 对象与数据库行之间的翻译 | 仓库管理员 |

为什么分层？因为业务会越来越复杂。如果所有代码都堆在 Controller 里，
改一个规则就要翻几百行，也没法单独测试。

### 3.5 ORM 与 MyBatis-Plus

Java 里操作数据库有两种风格：

- 直接写 SQL 字符串：麻烦、容易错、拼错一个引号就出问题
- ORM：把"一行数据"映射成"一个 Java 对象"，代码里操作对象即可

MyBatis-Plus 就是 ORM 工具。我们的 `User` 实体对应 `users` 表，
`UserMapper extends BaseMapper<User>` 之后，插入、按 ID 查询等方法自动就有。
我们不需要手写 SQL，框架会生成。

### 3.6 密码为什么不能明文存

数据库可能被拖库。如果密码是明文，所有用户账号直接泄露。
所以只存"密码的指纹"：哈希值。

哈希（Hash）是单向函数：`明文 -> 哈希` 容易，`哈希 -> 明文` 几乎不可能。
但纯哈希有两个问题：

- 相同密码产生相同哈希，容易被彩虹表反查
- 计算太快，暴力猜密码成本低

解决办法就是我们的 `PasswordHasher`：

- **加盐**：每个用户随机生成 16 字节 salt，同一个密码得到不同哈希
- **迭代**：PBKDF2 迭代 21 万次，让每次猜测都变慢

存储格式 `pbkdf2$210000$盐$哈希` 把参数也存下来，以后升级算法不影响旧数据。

### 3.7 会话与 Token

HTTP 本身无状态：服务器不记得上一次请求是谁。
登录成功后，服务器生成一个随机 token（UUID），把它存进 `user_sessions` 表，
并返回给前端。之后前端每次请求都带上 `Authorization: Bearer <token>`，
服务器查表就知道你是谁。

这就是"会话（Session）"：**服务端保存凭证，客户端只保存钥匙**。

## 4. 代码地图

| 文件 | 作用 |
| :--- | :--- |
| `IpoVideoApplication.java` | 程序入口，启动 Spring Boot |
| `common/Result.java` | 统一响应体 |
| `common/BusinessException.java` | 业务异常，携带错误码 |
| `common/GlobalExceptionHandler.java` | 把异常统一转成 JSON |
| `entity/User.java` | 用户表实体 |
| `entity/UserSession.java` | 会话表实体 |
| `mapper/UserMapper.java` | 用户表 CRUD |
| `mapper/UserSessionMapper.java` | 会话表 CRUD |
| `dto/RegisterRequest.java` | 注册参数 + 校验规则 |
| `dto/LoginRequest.java` | 登录参数 + 校验规则 |
| `dto/UserView.java` | 返回给前端的用户信息（不含密码哈希） |
| `dto/LoginResponse.java` | 登录返回值（token + 用户） |
| `service/PasswordHasher.java` | PBKDF2 密码哈希 |
| `service/AuthService.java` | 注册 / 登录 / 登出 / 按 token 找用户 |
| `controller/AuthController.java` | 注册、登录、登出接口 |
| `controller/MeController.java` | 当前用户信息接口 |
| `controller/HealthController.java` | 健康检查 |
| `config/AuthInterceptor.java` | 登录校验拦截器 |
| `config/WebConfig.java` | 注册拦截器、放行规则 |
| `resources/schema.sql` | 建表 SQL |
| `resources/application.properties` | 配置文件 |

## 5. 一次注册请求的完整旅程

1. Postman 发送 `POST /api/auth/register`，请求体是 JSON。
2. `GlobalExceptionHandler` 已就位，随时准备接住异常。
3. `AuthInterceptor` 发现该路径在放行名单里，跳过校验。
4. Spring 用 `@Valid` 检查 `RegisterRequest` 的注解规则，不合格直接返回 400。
5. `AuthController` 把请求交给 `AuthService.register`。
6. Service 查一次用户名是否已存在；不存在则生成 PBKDF2 哈希。
7. `UserMapper.insert(user)` 生成 SQL 插入一行。
8. 方法返回 `UserView`，Controller 包成 `Result`，Jackson 转成 JSON。
9. 浏览器拿到 `{"code":0,"message":"success","data":{...}}`。

## 6. 运行与验证

```powershell
cd D:\No1\DOVideo-AI-main\rebuild\backend
.\mvnw.cmd spring-boot:run
```

另开一个终端验证：

```powershell
curl http://localhost:9090/health

curl -X POST http://localhost:9090/api/auth/register `
  -H "Content-Type: application/json" `
  -d '{\"username\":\"coder_01\",\"password\":\"secret123\",\"nickname\":\"小白\"}'

curl -X POST http://localhost:9090/api/auth/login `
  -H "Content-Type: application/json" `
  -d '{\"username\":\"coder_01\",\"password\":\"secret123\"}'

curl http://localhost:9090/api/me -H "Authorization: Bearer <登录返回的token>"
```

> PowerShell 里手写 JSON 很容易踩引号转义的坑。如果 curl 报 400/500，
> 直接运行一键验证脚本，它会自动生成用户名并完成注册、登录、取用户信息：
>
> ```powershell
> cd D:\No1\DOVideo-AI-main\rebuild
> .\scripts\stage1-check.ps1
> ```

跑自动化测试：

```powershell
.\mvnw.cmd test
```

## 7. 作业（做完才算过关）

### 动手任务

给"当前用户"增加一个 `PUT /api/me/password` 接口：

1. 接收旧密码和新密码（新密码也要满足 8-128 位校验）。
2. 校验旧密码，错误返回 401。
3. 成功后用 `PasswordHasher.hash` 生成新哈希并更新数据库。
4. 加一个集成测试：旧密码登录能进，改密后用新密码登录成功、旧密码失败。

提示：`UserMapper.updateById(user)` 可以更新整行。

### 思考题（写进你的笔记）

1. 为什么注册时既要查重、又要靠数据库唯一索引兜底？
2. `UserView` 为什么不直接返回 `User` 对象？
3. 如果 token 被偷了怎么办？现在的设计能做什么、不能做什么？
4. 如果把 `passwordHash` 字段放进 `toString()`，会有什么风险？
5. 登录失败为什么说"账号或密码错误"，而不是"账号不存在"？

## 8. 面试自测题

1. 请描述一个 HTTP 请求在后端完整经过哪些层。
2. HTTP 状态码 200/400/401/409/500 分别代表什么？
3. 密码为什么要加盐并迭代哈希？和加密有什么区别？
4. 为什么接口返回值要统一结构？
5. 会话 token 是怎么生成、怎么校验、怎么过期的？

## 9. 这一阶段在简历上怎么说

> 使用 Java 21 + Spring Boot 3 搭建长视频分析平台后端骨架，实现基于
> MyBatis-Plus 的用户注册登录与会话管理；密码采用 PBKDF2 加盐迭代哈希存储；
> 通过统一响应体、参数校验、全局异常处理与拦截器保证接口规范和安全。

等你能独立讲清上面每个点，这句话才算真正属于你。
