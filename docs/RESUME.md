# IpoVideo 简历素材（填写版）

> 使用前先读 [STAGE-11.md](STAGE-11.md) 的“诚实边界清单”。

## 基本信息（占位，自己填）

```text
姓名：
求职意向：Java 后端开发工程师
学历/专业：
联系方式：
```

## 项目一句话

面向长视频内容理解的 AI Agent 平台：上传课程/会议视频后，系统异步完成
语音与画面理解，由 Agent 生成可回溯到时间戳的结构化结论。

## 技术栈

```text
Java 21 / Spring Boot 3 / MyBatis-Plus
MySQL 8 / Flyway / Redis / RocketMQ
MinIO / Qdrant / LangChain4j / DeepSeek
FFmpeg / Tesseract / Docker / GitHub Actions
```

## 项目描述（简历正文，可微调措辞）

> **IpoVideo：面向长视频内容理解的 AI Agent 平台**
>
> 技术栈：Java 21、Spring Boot 3、MyBatis-Plus、MySQL、Redis、RocketMQ、
> MinIO、Qdrant、LangChain4j、DeepSeek、FFmpeg、Tesseract
>
> - 实现注册登录与媒体管理：PBKDF2 加盐哈希、Redis 会话与登录限流；
> - 使用 RocketMQ 将视频分析任务异步化，消费者幂等执行，Redis 分布式锁
>   防止重复提交，任务状态机 PENDING/RUNNING/SUCCESS/FAILED 全量落库；
> - 基于 MinIO 实现大视频分片上传与断点续传，Redis 记录分片进度，
>   数据库仅存文件元数据；
> - 使用 FFmpeg 抽取音频与关键帧，结合 ASR/OCR 构建带时间轴的
>   VideoContext，供 Planner-Executor-Critic Agent 生成结构化结论，
>   结论基于视频证据而非凭空生成；
> - 使用 BGE-M3 Embedding + Qdrant 实现片段向量检索，Qdrant 不可用时
>   自动降级为关键词匹配，保证主链路不中断；
> - 使用 Flyway 管理数据库迁移，编写 23 个集成测试，并完成 Dockerfile、
>   docker-compose 与 GitHub Actions CI 配置。

## 可展开讲的亮点（面试用）

1. 异步可靠性：MQ 至少一次投递 + 任务状态幂等。
2. 成本控制：Agent 最多两轮 + 结构化 JSON + 解析失败重试一次。
3. 证据约束：结论绑定时间戳，Critic 校验，不通过不伪造成功。
4. 优雅降级：Qdrant/Embedding 挂了走关键词，任务不崩。
5. 真实踩坑：S3 分片 5MiB 规则、FFmpeg 选项改名、Broker 广播旧 IP。

## 诚实边界（面试前必须自查）

| 项 | 实际状态 |
| :--- | :--- |
| Docker/CI | 配置文件已写，本机未装 Docker，未实测 |
| VideoContext | 片段级时间戳，句子级定位未做 |
| 检索 | 真实 Qdrant + 真实 embedding 已测 |
| 前端 | 未做，岗位为后端 |
| 数据量 | 本地学习数据，未做压测 |

## 2 分钟自我介绍模板

```text
面试官好，我叫___，求职 Java 后端。最近独立完成了一个长视频 AI 分析平台：
用户上传视频并提出目标，系统通过 RocketMQ 异步处理，用 FFmpeg + ASR/OCR
把视频变成带时间轴的文本证据，再让 DeepSeek 以 Planner-Executor-Critic
的方式生成结构化结论。
这个项目覆盖了后端常见组件：MySQL、Redis、RocketMQ、MinIO、Qdrant，
我也踩过不少真实坑，比如消息重复消费、分片上传 5MiB 限制、Broker 广播地址
过期导致连接超时。我对后端可靠性设计比较感兴趣，希望有机会进一步学习。
```

## 面试高频追问答案速查

1. **为什么用 MQ 不用线程池？** 线程池是进程内排队，进程重启任务丢；
   MQ 独立持久化，支持多实例消费和重试。
2. **重复消费怎么办？** 消费前按任务 ID 查状态，终态直接跳过（幂等）。
3. **Redis 和 MySQL 分工？** Redis 放可重建的临时状态，MySQL 放业务真相。
4. **Agent 怎么防幻觉？** 证据约束 + Critic 校验 + 轮次上限，不通过就失败。
5. **Qdrant 挂了？** 日志告警 + 关键词检索降级，任务仍能完成。
