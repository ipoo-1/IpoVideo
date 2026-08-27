# Stage 7：LLM 接入 + Planner-Executor-Critic Agent

## 1. 本阶段目标

把 TaskWorker 的“模拟分析”换成真正的 AI 分析：

1. 接入 DeepSeek（通过 SiliconFlow，OpenAI 兼容协议）。
2. 让模型按固定 JSON 结构输出结论，程序能稳定解析。
3. 实现受控 Agent 工作流：Planner 拆任务 -> Executor 生成结论 -> Critic 校验质量，最多两轮。

## 2. 先建立一张地图

```text
用户目标："整理二叉树的核心知识点和复习题"
        │
        ▼
Planner  拆成 1-5 个可执行任务
        │
        ▼
Executor 基于任务生成结构化结论（标题、结论、建议）
        │
        ▼
Critic   检查：目标覆盖了吗？结论有证据吗？结构完整吗？
        │
        ├─ 通过 -> 保存结果，结束
        └─ 不通过 -> 带着批评意见进入第二轮（最多两轮）
```

为什么叫“受控 Agent”：模型负责理解和生成，程序负责状态、轮次、结构和预算。模型不能无限发挥，程序说了算。

## 3. 核心概念

### 3.1 LLM 是怎么被调用的

本质上就是一次 HTTP 请求：

```text
POST https://api.siliconflow.cn/v1/chat/completions
Authorization: Bearer <API Key>
Body: {
  "model": "deepseek-ai/DeepSeek-V3.2",
  "messages": [
    {"role": "system", "content": "你是 Planner，只输出 JSON..."},
    {"role": "user", "content": "用户目标：..."}
  ]
}
```

我们用 LangChain4j 封装这层网络细节，代码里只需要：

```java
chatModel.chat(SystemMessage.from(system), UserMessage.from(user));
```

### 3.2 模型没有记忆

每次调用模型都是“失忆”的：上一轮的对话它不记得。所以每一轮调用都要把**需要的上下文完整放进 prompt**，这也是为什么 Agent 需要程序来保存状态（比如 Plan、Critic 意见），而不是靠模型自己记住。

### 3.3 结构化输出

模型默认返回自由文本，程序没法稳定解析。解法：在 prompt 里要求“只返回 JSON”，然后程序用 Jackson 解析成 Java 对象；解析失败就重试一次。

```java
record AgentPlan(String understoodGoal, List<String> tasks) {}
```

### 3.4 Planner / Executor / Critic 为什么拆开

一次调用做太多事容易漏。拆开后：

| 角色 | 职责 | 输出 |
| :--- | :--- | :--- |
| Planner | 理解目标、拆任务 | `AgentPlan` |
| Executor | 按任务生成结论 | `AgentResult` |
| Critic | 检查质量、指出问题 | `CriticResult` |

关键：**Critic 的意见必须改变下一轮的输入**（比如补充遗漏、修正结论），否则循环只是重复烧钱。

### 3.5 受控循环与预算

- 最多两轮：第一轮生成，第二轮按 Critic 意见修订。
- 每一轮都记录轮次，超预算直接终止，绝不无限循环。
- 这是成本控制的核心：AI 调用要花钱，程序必须给模型套上“笼子”。

### 3.6 幻觉与证据（本阶段边界）

模型可能编造内容（幻觉）。真正约束幻觉要靠“证据链”：结论绑定视频时间戳，程序校验证据存在。那是 Stage 8 的事；本阶段先保证**结构可靠、流程可控**，简历上要如实说“Stage 7 完成结构化 Agent 流程，证据校验在 Stage 8”。

## 4. 代码地图（本阶段新增/改动）

| 文件 | 作用 |
| :--- | :--- |
| `pom.xml` | 新增 langchain4j-open-ai |
| `application.properties` | DeepSeek api-key / base-url / model / timeout |
| `config/DeepSeekConfig.java` | 创建 ChatModel Bean |
| `dto/AgentPlan.java` | Planner 输出 |
| `dto/AgentResult.java` | Executor 输出 |
| `dto/CriticResult.java` | Critic 输出 |
| `service/AgentLoopService.java` | P-E-C 循环编排 |
| `service/TaskWorker.java` | 把模拟结果换成 AgentLoop 调用 |

## 5. 一次分析任务的完整旅程（Stage 7 版）

1. 用户上传视频、提交目标，任务经 RocketMQ 到达消费者。
2. `TaskWorker` 开始执行：状态 RUNNING，进度逐阶段推进。
3. 进入 `AgentLoopService.run(goal)`。
4. Planner 调用模型，把目标拆成任务列表（JSON）。
5. Executor 调用模型，生成 `AgentResult`（标题、结论、建议）。
6. Critic 调用模型，返回 `CriticResult`。
7. 不通过且未到两轮：带着意见再来一轮 Executor。
8. 通过或到上限：返回结果，`TaskWorker` 把 JSON 存入 `analysis_tasks.result`，状态 SUCCESS。

## 6. 运行与验证

### 6.1 配置 API Key

在 `application.properties`（或环境变量）配置：

```properties
ai.deepseek.api-key=${SILICONFLOW_API_KEY:}
ai.deepseek.base-url=${SILICONFLOW_BASE_URL:https://api.siliconflow.cn/v1}
ai.deepseek.model=${LLM_MODEL:deepseek-ai/DeepSeek-V3.2}
ai.deepseek.timeout-seconds=${LLM_TIMEOUT_SECONDS:120}
```

### 6.2 启动后端并跑一次任务

```powershell
cd D:\No1\DOVideo-AI-main\rebuild\backend
.\mvnw.cmd spring-boot:run
```

跑 `stage3-demo.ps1`，然后查任务结果：

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' -u dovideo -pdovideo123 -h 127.0.0.1 --protocol=TCP -e "USE dovideo; SELECT id, status, LEFT(result, 500) FROM analysis_tasks ORDER BY id DESC LIMIT 3;"
```

`result` 应该是可解析的 JSON（AgentResult）。

## 7. 作业

### 动手任务

1. 在 SiliconFlow 创建 API Key 并配置，跑通一次真实分析。
2. 故意把用户目标改成“这个视频讲了什么”，观察 Planner 输出的任务数（应该在 1-5 个之间）。
3. 在日志里找到 `agent_round=1` 和 `agent_round=2`（如果触发第二轮），确认轮次有记录。

### 思考题

1. 为什么模型每次调用都要带完整上下文？
2. Critic 的反馈如果不影响下一轮输入，会发生什么？
3. 为什么要限制最多两轮？不限制会怎样？
4. 结构化输出解析失败时，重试一次的意义是什么？

## 8. 面试自测题

1. 一次 LLM API 调用的请求长什么样？
2. Planner / Executor / Critic 分别负责什么？
3. 为什么 Agent 需要“程序控制状态和轮次”，而不是让模型自由发挥？
4. 怎么让模型返回可解析的结构化数据？解析失败怎么办？
5. 成本控制怎么做？（轮次、超时、预算）
6. 本阶段结果和 Stage 8 证据校验的关系是什么？

## 9. 这一阶段在简历上怎么说

> 使用 LangChain4j 接入 DeepSeek，将视频分析从模拟实现升级为受控 Agent 工作流：
> Planner-Executor-Critic 三阶段编排，模型按固定 JSON 输出，程序负责状态机、
> 轮次与预算控制（最多两轮），结果结构化落库。

等你能独立回答第 8 节全部题目，这句话才算真正属于你。
