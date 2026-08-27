# Stage 3：异步任务 + 线程池 + SSE 进度

## 1. 本阶段目标

解决产品最核心的工程问题：**视频分析很慢，不能让用户一直等着接口返回。**

实现：

1. 用户上传视频、提交分析目标后，接口立刻返回任务 ID。
2. 后台线程池慢慢执行“分析”（本阶段是模拟流程）。
3. 前端通过 SSE 实时收到进度，断线后可以从任务状态恢复。

## 2. 先建立一张地图

同步 vs 异步：

```text
同步（Stage 1-2 的做法）：
用户请求 -> 处理 2 分钟 -> 才返回
用户一直转圈，浏览器超时，体验极差

异步（Stage 3 的做法）：
用户请求 -> 立刻返回 taskId
               后台线程慢慢处理
               SSE 推送进度：20% -> 45% -> 70% -> 100%
```

任务状态机：

```text
PENDING（等待执行）
    │
    ▼
RUNNING（执行中）
    │
    ├──► SUCCESS（成功，带结果）
    └──► FAILED（失败，带错误信息）
```

## 3. 核心概念

### 3.1 为什么需要异步

视频分析要几秒到几分钟。如果接口同步执行：

- 用户请求一直挂着，前端只能转圈
- 一个用户占住一个请求线程，并发一高服务就死
- 浏览器和网关通常有超时时间，直接断连

异步化后：**提交即返回，结果稍后自取**。这是所有“重任务”系统的标准解法，
也是本项目最值得写进简历的工程决策之一。

### 3.2 线程池

线程不是越多越好，创建和销毁都有成本。线程池就是“预先养一批工人，任务来了排队干活”：

```java
ThreadPoolTaskExecutor
  核心线程 2 个（常驻）
  最大线程 4 个（忙时扩容）
  队列 100 个（再满就排队）
```

见 [ThreadPoolConfig.java](../backend/src/main/java/com/dovideo/config/ThreadPoolConfig.java)。
本阶段用线程池实现异步；Stage 5 会换成 RocketMQ，让“提交任务”和“执行任务”完全解耦。

### 3.3 任务状态机

任务必须有明确状态，前端和后台才好协作：

| 状态 | 含义 |
| :--- | :--- |
| PENDING | 已创建，等待执行 |
| RUNNING | 正在分析 |
| SUCCESS | 完成，`result` 有内容 |
| FAILED | 失败，`error_message` 有原因 |

表结构见 [V4__add_analysis_tasks.sql](../backend/src/main/resources/db/migration/V4__add_analysis_tasks.sql)。

### 3.4 SSE：服务器单向推送

SSE（Server-Sent Events）是 HTTP 长连接，服务器可以不断往客户端推消息。
适合“进度条、通知”这类单向实时场景。

对比：

| 方式 | 方向 | 适用 |
| :--- | :--- | :--- |
| 轮询 | 客户端反复问 | 简单但浪费 |
| SSE | 服务器单向推 | 进度、通知 |
| WebSocket | 双向 | 聊天、协作 |

浏览器 `EventSource` 不能自定义请求头，所以我们允许 `?token=xxx` 传登录凭证
（[AuthInterceptor](../backend/src/main/java/com/dovideo/config/AuthInterceptor.java)）。

### 3.5 本地文件上传

Stage 3 先用本地磁盘保存视频，数据库只存元数据（路径、MD5、上传时间）。
Stage 6 会升级成 MinIO 对象存储，Controller/Service 的接口不用大变。

### 3.6 权限边界

任务属于用户：创建任务时校验“视频是不是你的”，查询时校验“任务是不是你的”，
别人访问一律 403。这是后端安全的基本功。

## 4. 代码地图

| 文件 | 作用 |
| :--- | :--- |
| `V4__add_analysis_tasks.sql` | 分析任务表 + 索引 |
| `entity/AnalysisTask.java` | 任务实体 |
| `entity/MediaFile.java` | 媒体文件实体（Stage 3 启用） |
| `mapper/AnalysisTaskMapper.java` | 任务表 CRUD |
| `mapper/MediaFileMapper.java` | 媒体表 CRUD |
| `dto/CreateTaskRequest.java` | 创建任务参数 |
| `dto/TaskView.java` | 任务视图 |
| `dto/TaskEvent.java` | SSE 事件体 |
| `dto/TaskStatus.java` | 状态枚举 |
| `dto/TaskStage.java` | 模拟分析阶段 |
| `config/ThreadPoolConfig.java` | 后台线程池 |
| `service/MediaService.java` | 本地保存文件 + 元数据 |
| `service/TaskService.java` | 创建/查询任务，提交线程池 |
| `service/TaskWorker.java` | 后台分析工人，更新状态 + 推 SSE |
| `service/TaskEventService.java` | SSE 连接管理 |
| `controller/MediaController.java` | 上传接口 |
| `controller/TaskController.java` | 创建/查询/SSE 接口 |

## 5. 一次任务创建的完整旅程

1. 用户登录，拿到 token。
2. `POST /api/media/upload` 上传视频，文件存本地，MySQL 的 `media_files` 多一行。
3. `POST /api/tasks` 提交 `{mediaId, goal}`，接口立刻返回 `taskId` 和 `status=PENDING`。
4. `TaskService` 把任务插入 `analysis_tasks`，然后 `taskExecutor.execute(...)` 丢给线程池。
5. `TaskWorker.run` 开始：状态改 RUNNING，逐阶段 `Thread.sleep` 模拟耗时。
6. 每个阶段更新数据库并 `taskEventService.publish` 推送 SSE 事件。
7. 全部完成，状态改 SUCCESS，写入结果，SSE 推送完成事件并关闭连接。
8. 前端轮询 `GET /api/tasks/{id}` 也能拿到最新状态，即使 SSE 断了也不丢。

## 6. 运行与验证

### 6.1 重启后端（让 V4 迁移在 MySQL 上执行）

```powershell
cd D:\No1\DOVideo-AI-main\rebuild\backend
.\mvnw.cmd spring-boot:run
```

日志会看到 `Successfully applied migration to version 4`。

### 6.2 走一遍异步流程

先登录拿 token：

```powershell
curl.exe -X POST http://localhost:9090/api/auth/login -H "Content-Type: application/json" -d '{"username":"coder_155712","password":"secret123"}'
```

上传视频（需要一个真实文件，比如 `test.mp4`）：

```powershell
curl.exe -X POST http://localhost:9090/api/media/upload -H "Authorization: Bearer <token>" -F "file=@test.mp4"
```

创建任务：

```powershell
curl.exe -X POST http://localhost:9090/api/tasks -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"mediaId":<视频id>,"goal":"整理核心知识点"}'
```

轮询任务状态（多执行几次，能看到状态从 PENDING 变 SUCCESS）：

```powershell
curl.exe http://localhost:9090/api/tasks/<taskId> -H "Authorization: Bearer <token>"
```

观察 SSE 进度（保持连接，看实时推送）：

```powershell
curl.exe -N "http://localhost:9090/api/tasks/<taskId>/events?token=<token>"
```

## 7. 作业

### 动手任务：写任务列表接口

新增 `GET /api/tasks`，返回“当前用户的所有任务，按创建时间倒序”。

提示：

- `TaskService` 里用 `QueryWrapper` 的 `.eq("user_id", userId).orderByDesc("created_at")`
- `AnalysisTaskMapper.selectList(query)` 返回 `List<AnalysisTask>`
- Controller 返回 `Result<List<TaskView>>`

### 思考题

1. 为什么创建任务的接口必须立刻返回？
2. 如果线程池队列满了，会发生什么？Stage 5 怎么解决？
3. SSE 断线后，前端怎么恢复进度？
4. 为什么任务状态要有 FAILED，而不是只有成功/失败？

## 8. 面试自测题

1. 同步请求和异步任务有什么区别？什么场景必须异步？
2. 线程池的核心线程、最大线程、队列分别是什么？
3. 任务状态机为什么要设计成 PENDING/RUNNING/SUCCESS/FAILED？
4. SSE 和 WebSocket 有什么区别？各自适合什么场景？
5. 如果服务器重启，正在 RUNNING 的任务会怎样？当前设计有什么风险？

## 9. 这一阶段在简历上怎么说

> 将视频分析改造为异步任务架构：任务表 + 状态机 + 线程池执行，
> 提交接口即时返回任务 ID，SSE 实时推送阶段进度，支持断线后轮询恢复；
> 同时实现文件上传、MD5 校验与基于用户的资源权限隔离。

注意第 5 题是个坑：当前设计服务器重启后 RUNNING 任务不会自动恢复，
这正好是 Stage 5 Checkpoint 要解决的问题。面试时要诚实说“这是下一阶段目标”。
