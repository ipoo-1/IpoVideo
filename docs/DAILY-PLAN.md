# IpoVideo 21 天速成计划（每天 8 小时）

## 使用说明

- 前提：已有 Java 基础（变量、类、对象、方法、集合、异常、能读懂简单代码）。
- 如果完全零基础：先花 5-7 天补 Java 语法速成，再进入本计划。
- 如果你能看懂请求链路但写不出代码：先执行 [JAVA-BASICS.md](JAVA-BASICS.md) 的 7 天项目式补课，再继续本计划。
- 每天固定节奏：上午学概念（2h）+ 看代码（2h），下午动手实现（3h），晚上作业与复盘（1h）。
- 完成标准：当天“验收”能独立讲清，作业能跑通，才进入下一天；否则第二天先补。
- 原则：先跑通再深挖，面试考点优先，不理解的记 TODO，不卡死在某一行。

## 21 天总览

| 天 | 主题 | 产出 |
| :--- | :--- | :--- |
| 1 | 后端全景 + 跑通 Stage 1 | 能启动服务并调用 4 个接口 |
| 2 | 读懂 Stage 1 代码 + 作业 | 修改密码接口完成 |
| 3 | Stage 1 验收 + SQL/MySQL 基础 | 能独立回答面试题，建表熟练 |
| 4 | Stage 2：Flyway + MySQL 接入 | 项目切到 MySQL，迁移文件管理表结构 |
| 5 | Stage 2：事务 + 索引 | 事务化注册流程 + 索引设计 |
| 6 | Stage 3：异步任务 + 线程池 | 上传即返回，后台任务执行 |
| 7 | Stage 3：SSE 进度推送 | 前端能看到任务阶段进度 |
| 8 | Stage 3 验收 + Redis 基础 | 理解缓存、TTL、常见数据结构 |
| 9 | Stage 4：会话入 Redis + 登录限流 | 登录会话不再依赖数据库表 |
| 10 | Stage 4：分布式锁 + 幂等 | 防重复提交分析任务 |
| 11 | Stage 5：RocketMQ 概念 + 生产端 | 任务投递 MQ，接口快速返回 |
| 12 | Stage 5：消费端 + 重试 + 死信 | 消费者可靠处理，失败可重投 |
| 13 | Stage 6：MinIO 对象存储 | 视频文件存入对象存储 |
| 14 | Stage 6：分片上传 + 断点续传 | 大文件断点续传 API 完成 |
| 15 | Stage 7：LLM 接入 + 结构化输出 | DeepSeek 调用成功，JSON 解析稳定 |
| 16 | Stage 7：Planner-Executor-Critic | Agent 第一版跑通 |
| 17 | Stage 7：证据校验 + 预算控制 + 作业 | 结论绑定时间戳，两轮限制 |
| 18 | Stage 8 速成：FFmpeg + ASR + OCR | 能讲原理，完成简化版 VideoContext |
| 19 | Stage 9 速成：Embedding + Qdrant | 能讲原理，完成简化向量检索 |
| 20 | Stage 10：测试 + Docker Compose + 部署 | 一键启动全链路 |
| 21 | Stage 11：简历 + 模拟面试 | 简历终稿 + 项目问答过关 |

## 逐日安排

### Day 1：后端全景 + 跑通 Stage 1

- 上午：读 [PRODUCT.md](PRODUCT.md) 和 [ROADMAP.md](ROADMAP.md)，理解产品为什么需要这个架构；学习 HTTP、REST、JSON。
- 下午：启动 Stage 1 服务，用 Postman/curl 调通 `/health`、注册、登录、`/api/me`。
- 晚上：写一篇 500 字“一次注册请求的完整旅程”。
- 验收：不翻文档能说出请求经过哪些层。

### Day 2：读懂 Stage 1 代码 + 修改密码作业

- 上午：按 [STAGE-1.md](STAGE-1.md) 代码地图逐文件读，重点理解 Controller/Service/Mapper 职责。
- 下午：独立实现 `PUT /api/me/password`（校验旧密码、更新新密码哈希）。
- 晚上：补集成测试并跑 `.\mvnw.cmd test`。
- 验收：测试全绿；能讲清为什么密码要加盐迭代哈希。

### Day 3：Stage 1 验收 + SQL/MySQL 基础

- 上午：回答 STAGE-1 第 8 节 5 道面试题，卡住的重看代码。
- 下午：学习 SQL 增删改查、主键、唯一索引、外键、事务 ACID；在 H2 控制台手写建表与查询。
- 晚上：看 MySQL 8 与 H2 的差异（自增、时间类型、字符集）。
- 验收：面试题全部能讲；能独立写 `CREATE TABLE`。

### Day 4：Stage 2：Flyway + MySQL 接入

- 上午：学习 Flyway 为什么存在（表结构版本管理），读参考项目 `V1__create_core_tables.sql`。
- 下午：安装/启动 MySQL（Docker 或本机），把项目数据源切到 MySQL，用 Flyway 管理建表。
- 晚上：写 `V2__xxx.sql` 练习一次表结构升级。
- 验收：删库重建后一条命令恢复全部表结构。

### Day 5：Stage 2：事务 + 索引

- 上午：学习 `@Transactional`、回滚、脏读/不可重复读/幻读。
- 下午：把注册（建用户 + 建默认会话）改成事务；为 `users.username`、`user_sessions.token` 设计索引。
- 晚上：写一个“故意失败回滚”的测试。
- 验收：能解释为什么注册要事务、查询为什么要索引。

### Day 6：Stage 3：异步任务 + 线程池

- 上午：学习同步/异步、线程池参数、任务状态机。
- 下午：新增 `analysis_tasks` 表和任务服务；上传视频后立刻返回 `taskId`，后台线程执行“模拟分析”。
- 晚上：给任务状态加 PENDING/RUNNING/SUCCESS/FAILED。
- 验收：接口 1 秒内返回，后台任务继续执行。

### Day 7：Stage 3：SSE 进度推送

- 上午：学习 SSE 与 WebSocket 区别、SSE 适用场景。
- 下午：实现 `/api/tasks/{id}/events`，推送阶段进度事件。
- 晚上：写一个客户端脚本接收进度并打印。
- 验收：前端（或脚本）能实时看到任务阶段变化。

### Day 8：Stage 3 验收 + Redis 基础

- 上午：验收 Stage 3：能讲清异步解决了哪个产品问题；学习 Redis 五种数据结构、TTL。
- 下午：安装 Redis（Docker 或本机），用命令行练习 String/Hash/Set/ZSet。
- 晚上：把任务进度缓存到 Redis，读取时先查缓存。
- 验收：能解释“Redis 丢了没事，MySQL 丢了才可怕”。

### Day 9：Stage 4：会话入 Redis + 登录限流

- 上午：学习缓存更新策略、过期时间、key 命名规范。
- 下午：把 `user_sessions` 表改成 Redis `String`（key `auth:session:{token}`）；实现登录失败次数限流。
- 晚上：写测试验证登录 8 次失败后触发 429。
- 验收：重启服务后登录状态仍有效（会话在 Redis）。

### Day 10：Stage 4：分布式锁 + 幂等

- 上午：学习 Redisson 分布式锁原理、幂等设计。
- 下午：用“内容哈希 + 用户目标”做分析任务防重；同一任务重复提交只创建一个。
- 晚上：写并发测试（10 个线程同时提交）。
- 验收：能解释为什么单机 `synchronized` 不够。

### Day 11：Stage 5：RocketMQ 概念 + 生产端

- 上午：学习消息队列解决什么（削峰、解耦、异步）、Topic/Consumer/Producer。
- 下午：启动 RocketMQ（Docker），项目接入 producer，提交分析任务时发消息。
- 晚上：验证接口快速返回、消息出现在队列。
- 验收：能画出“接口 -> MQ -> 消费者”时序图。

### Day 12：Stage 5：消费端 + 重试 + 死信

- 上午：学习消费确认、重试、死信队列、幂等消费。
- 下午：实现消费者处理任务、失败消息进死信 Topic；实现按任务状态跳过重复消费。
- 晚上：写“消费者崩溃后重启，消息不丢不重”的验证。
- 验收：能解释至少一次投递与幂等消费的关系。

### Day 13：Stage 6：MinIO 对象存储

- 上午：学习为什么文件不进 MySQL（数据库 vs 对象存储），MinIO/S3 概念。
- 下午：启动 MinIO，实现上传接口，文件存入桶，数据库只存元数据。
- 晚上：补媒体文件表 `media_files`，字段对齐参考项目。
- 验收：上传后能从 MinIO 下载回来。

### Day 14：Stage 6：分片上传 + 断点续传

- 上午：学习分片上传流程、Redis 记录分片、MD5 校验。
- 下午：实现初始化/上传分片/完成合并三段接口；前端脚本模拟断点重传。
- 晚上：验证中断后只重传缺失分片。
- 验收：能讲清“为什么 Redis 丢分片记录不会丢数据”。

### Day 15：Stage 7：LLM 接入 + 结构化输出

- 上午：学习大模型 API 调用、Prompt 设计、JSON 结构化输出、超时与重试。
- 下午：接入 SiliconFlow/DeepSeek，实现“按目标生成总结”接口，要求模型返回固定 JSON。
- 晚上：处理模型返回非法 JSON 的重试。
- 验收：连续 10 次调用都能解析出结构化结果。

### Day 16：Stage 7：Planner-Executor-Critic

- 上午：学习 Agent 与工作流区别，理解 Planner/Executor/Critic 各自职责。
- 下午：实现 `AgentLoopService`：Planner 拆任务 -> Executor 生成结论 -> Critic 检查。
- 晚上：打印每轮日志，观察模型行为。
- 验收：能画出 AgentLoop 循环图并讲清为什么需要 Critic。

### Day 17：Stage 7：证据校验 + 预算控制

- 上午：学习结论绑定时间戳、证据存在性校验、轮次与 Token 预算。
- 下午：实现 Critic 反馈驱动第二轮的补检索/改写；最大两轮，超预算终止。
- 晚上：完成 Stage 7 作业：同一目标跑 3 个视频，比较结论可追溯率。
- 验收：能解释“程序规则 + 模型判断”为什么比纯模型可靠。

### Day 18：Stage 8 速成：FFmpeg + ASR + OCR

- 上午：学习 FFmpeg 切音频/抽关键帧、ASR 转写、Tesseract OCR 原理。
- 下午：用命令行手工处理一个短视频，再在服务里封装调用，合并成 `VideoSegment`（时间戳 + 语音 + 画面文字）。
- 晚上：对照参考项目 `VideoContext` 相关代码，确认自己理解。
- 验收：能讲清 ASR 和 OCR 为什么是两条并行分支。

### Day 19：Stage 9 速成：Embedding + Qdrant

- 上午：学习向量、Embedding、余弦相似度、向量数据库与倒排索引区别。
- 下午：接入 BGE-M3 生成视频片段向量，存入 Qdrant，实现“按目标召回 TopK 片段”。
- 晚上：实现 Qdrant 不可用时降级为关键词匹配。
- 验收：能讲清“混合检索”和“优雅降级”在简历里的含义。

### Day 20：Stage 10：测试 + Docker Compose + 部署

- 上午：学习单元测试 vs 集成测试、Dockerfile、Compose 编排。
- 下午：为关键服务补测试；编写 Dockerfile 和 docker-compose.yml，一键启动 MySQL/Redis/MinIO/RocketMQ + 后端。
- 晚上：写 README 部署章节，完整跑一遍从零到可用。
- 验收：新电脑上按文档 30 分钟内启动全链路。

### Day 21：Stage 11：简历 + 模拟面试

- 上午：整理每个阶段产出，按实际完成度写简历项目描述；参考 [ROADMAP.md](ROADMAP.md) 的最终话术。
- 下午：模拟面试：自我介绍、项目链路、最难的地方、数据为什么放不同存储、Agent 为什么这样设计。
- 晚上：把回答录音/录屏回放，修正表达。
- 验收：不翻资料能完整讲 5 分钟项目。

## 每周验收

- 第 1 周结束：注册登录 + MySQL + 异步任务全部可运行，能画出完整请求链路。
- 第 2 周结束：Redis + RocketMQ + MinIO 接入完成，能讲清每个中间件解决的产品问题。
- 第 3 周结束：Agent 分析链路可运行，简历和面试问答过关。

## 速成原则

1. 先跑通再深挖：功能能用，再去研究细节。
2. 面试考点优先：每个阶段最后一天的“验收”就是面试考点。
3. 诚实记录完成度：简历只写你真正实现并讲得清的部分；Stage 8/9 如果只做了简化版，就写“实现简化版 + 理解原理”，不要冒充完整版。
4. 卡住不超过 30 分钟：先记 TODO，继续推进，晚上或第二天回头解决。
