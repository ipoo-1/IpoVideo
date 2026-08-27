# Stage 5：RocketMQ（削峰、解耦、可靠投递）

## 1. 本阶段目标

把“创建任务”和“执行任务”彻底解耦：任务提交后投递到 RocketMQ，接口立刻返回；
消费者拿到消息后再执行分析。Stage 3 的线程池升级为消息队列，解决三个问题：

1. 线程池容量有限，高峰会拒绝任务；MQ 用队列把流量先接住。
2. 创建任务的接口不再依赖执行任务的服务，两者可以独立扩展。
3. 消息投递有重试机制，任务不容易因为一次抖动就丢失。

## 2. 先建立一张地图

```text
Stage 3（线程池，进程内异步）：
API -> 线程池 -> TaskWorker
       └ 任务只存在于这一个进程里，进程挂=任务丢

Stage 5（RocketMQ，进程外异步）：
API -> RocketMQ Broker -> 消费者 -> TaskWorker
       └ 消息存在 Broker 上，API 进程或消费者挂了，消息不丢
```

关键区别：**消息队列是独立于我们的应用进程运行的中间件**，它替我们保管“还没干完的活”。

## 3. 核心概念

### 3.1 消息队列解决什么问题

| 问题 | 线程池方案 | RocketMQ 方案 |
| :--- | :--- | :--- |
| 高峰打满 | 拒绝新任务 | 先堆到队列，慢慢消费 |
| 解耦 | 生产者和执行者同一个进程 | 两个进程，独立部署 |
| 可靠性 | 进程重启任务丢 | 消息持久化，可重试 |

### 3.2 四个角色

| 角色 | 类比 | 端口 |
| :--- | :--- | :--- |
| Producer 生产者 | 寄件人（我们的 API） | - |
| Broker | 快递仓库（真正存消息） | 10911 |
| Consumer 消费者 | 收件人（我们的分析服务） | - |
| Nameserver | 快递地址簿（让买卖双方互相找到） | 9876 |

### 3.3 Topic：消息的分类信箱

Topic 就是“一类消息的集合”。我们项目里建 `video-analysis-topic`，
所有“请分析这个视频”的消息都投进这个信箱。

### 3.4 至少一次投递 + 幂等消费（重点）

RocketMQ 的投递语义是“**至少一次**”：消息可能被重复投递给消费者。

所以消费者必须**幂等**：同样的消息处理两次，结果不能变坏。
我们的做法：消费者先查任务状态，任务已经在 RUNNING/SUCCESS 就直接跳过，
不重复跑分析（这正是 Stage 4 防重锁思路的延续）。

### 3.5 消费确认与重试

消费者处理成功后要告诉 Broker“这条我处理完了”（ACK）。
如果消费者抛异常，Broker 会按策略重新投递；多次失败的消息进入死信队列，留给人处理。

## 4. 代码地图（本阶段新增/改动）

| 文件 | 作用 |
| :--- | :--- |
| `pom.xml` | 新增 rocketmq-spring-boot-starter |
| `application.properties` | nameserver、producer group、topic、consumer group |
| `dto/AnalysisTaskMsg.java` | 消息体（taskId、mediaId、userId、goal） |
| `service/TaskService.java` | 创建任务后改为投递 MQ，不再直接调用线程池 |
| `consumer/VideoAnalysisConsumer.java` | 监听 Topic，收到消息后执行 TaskWorker |
| `TaskWorker.java` | 保持不变（真正的分析工人） |

## 5. 一次任务创建的完整旅程（MQ 版）

1. 用户 `POST /api/tasks`。
2. `TaskService` 检查视频归属、防重锁、插入任务（status=PENDING）。
3. `RocketMQTemplate.convertAndSend(topic, msg)` 把消息投到 Broker，接口立刻返回。
4. Broker 保存消息，通知消费者。
5. `VideoAnalysisConsumer.onMessage` 收到消息，调用 `taskWorker.run(taskId)`。
6. `TaskWorker` 更新状态、逐阶段执行、推 SSE，最终 SUCCESS。
7. 消费者正常返回，Broker 认为消费成功。

## 6. 运行与验证

### 6.1 启动 RocketMQ（本机已装二进制版）

```powershell
# 终端 1：Nameserver
D:\RcoketMQ\rocketmq-all-5.3.1-bin-release\rocketMQ\bin\mqnamesrv.cmd

# 终端 2：Broker
D:\RcoketMQ\rocketmq-all-5.3.1-bin-release\rocketMQ\bin\mqbroker.cmd -n 127.0.0.1:9876
```

### 6.2 启动后端

```powershell
cd D:\No1\DOVideo-AI-main\rebuild\backend
.\mvnw.cmd spring-boot:run
```

### 6.3 验证消息真的走了 MQ

跑一次任务创建（可用 stage3-demo.ps1），然后用 mqadmin 查看：

```powershell
D:\RcoketMQ\rocketmq-all-5.3.1-bin-release\rocketMQ\bin\mqadmin.cmd topicList -n 127.0.0.1:9876
D:\RcoketMQ\rocketmq-all-5.3.1-bin-release\rocketMQ\bin\mqadmin.cmd consumerProgress -n 127.0.0.1:9876 -g video-analysis-consumer
```

能看到 `video-analysis-topic` 和消费进度，说明链路通了。

## 7. 作业

### 动手任务

1. 用 mqadmin 新建一个测试 Topic：
   ```powershell
   mqadmin.cmd updateTopic -n 127.0.0.1:9876 -b 127.0.0.1:10911 -t hello-topic
   ```
2. 用 `mqadmin topicList` 确认它出现。
3. 思考：如果消费者处理消息时抛异常，Broker 会怎么处理？

### 思考题

1. 线程池和消息队列的本质区别是什么？
2. 什么是“至少一次投递”？为什么消费者必须幂等？
3. 我们的消费者怎么实现幂等？（提示：处理前查任务状态）
4. 如果 Broker 挂了，已经投递的消息会怎样？生产环境怎么避免单点？

## 8. 面试自测题

1. 消息队列解决什么问题？举你项目里的例子。
2. Producer / Broker / Consumer / Nameserver 各自职责？
3. 什么是 Topic？为什么按主题分类？
4. 至少一次投递和幂等消费的关系？
5. 消息重复消费了会怎样？你的系统怎么兜底？
6. 为什么说 MQ 比线程池更可靠？

## 9. 这一阶段在简历上怎么说

> 使用 RocketMQ 将视频分析任务从线程池升级为消息驱动：任务提交后投递到
> video-analysis-topic 立即返回，消费者异步执行分析；结合任务状态机实现
> 幂等消费，避免消息重复投递导致重复执行与重复调用 AI 成本。

等你能独立回答第 8 节全部题目，这句话才算真正属于你。
