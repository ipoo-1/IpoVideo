# Stage 9：Embedding + Qdrant 向量检索

## 1. 本阶段目标

长视频上下文太大，不能把所有片段都塞给模型。Stage 9 让系统**按用户目标只召回最相关的片段**：

1. 把视频片段变成向量（Embedding），存进 Qdrant。
2. 用户目标也转成向量，检索 TopK 最相关片段。
3. Qdrant 不可用时降级为关键词匹配，主链路不崩。

## 2. 先建立一张地图

```text
构建 VideoContext 后：
  每个片段 -> Embedding 向量 -> 存 Qdrant

分析任务：
  用户目标 -> Embedding 向量 -> Qdrant 相似度检索 TopK
                                    │
                                    ▼
                    最相关的片段作为证据喂给 Agent
                                    │
                    Qdrant 挂了？   │
                        ▼          │
                降级：关键词匹配     │
                                    ▼
                          Agent 生成结论
```

## 3. 核心概念

### 3.1 什么是 Embedding（向量化）

把一段文字变成一串数字（向量），语义相近的文字向量距离近：

```text
"二叉树的前序遍历"  -> [0.12, -0.34, ...]
"树的遍历方式"      -> [0.11, -0.31, ...]   （近）
"如何做红烧肉"      -> [0.87, 0.42, ...]    （远）
```

我们用 SiliconFlow 的 `BAAI/bge-m3` 模型生成向量（复用 API Key）。

### 3.2 向量检索 vs 关键词检索

| | 关键词（倒排索引） | 向量（语义检索） |
| :--- | :--- | :--- |
| 匹配方式 | 字面相同 | 语义相近 |
| 例子 | 搜"遍历"找不到"前序" | "遍历"能召回"前序" |
| 缺点 | 同义改写就失效 | 需要向量库，成本高 |

### 3.3 Qdrant 是什么

Qdrant 是开源向量数据库，核心概念：

| 概念 | 类比 |
| :--- | :--- |
| Collection | 一张“向量表” |
| Point | 一行数据（向量 + payload） |
| 相似度 | 余弦距离，值越大越相似 |

### 3.4 混合检索与优雅降级

“混合检索” = 关键词召回 + 向量召回合并。
“优雅降级” = Qdrant 或 Embedding 服务不可用时，退到本地关键词匹配，任务照样能跑，只是精度下降。

这是面试高频考点：**外部依赖要可降级，不能因为一个辅助服务挂了就把主流程搞崩。**

## 4. 代码地图（本阶段新增/改动）

| 文件 | 作用 |
| :--- | :--- |
| `utils/EmbeddingUtils.java` | 调 SiliconFlow embeddings 接口 |
| `service/VectorStoreService.java` | Qdrant 建集合、存向量、检索；失败降级关键词 |
| `dto/VideoEvidenceHit.java` | 检索命中的片段 |
| `service/VideoContextService.java` | 构建上下文时同时写向量 |
| `service/TaskWorker.java` | 检索 TopK 片段作为 Agent 证据 |

## 5. 一次分析的完整旅程（Stage 9 版）

1. VideoContext 构建完成，每个片段生成向量存入 Qdrant。
2. 用户目标转成向量，Qdrant 返回 TopK 最相关片段。
3. 如果 Qdrant/Embedding 不可用，关键词匹配兜底。
4. TopK 片段作为证据拼进 Agent prompt。
5. Agent 只基于召回的证据生成结论。

## 6. 运行与验证

### 6.1 启动 Qdrant

Windows 直接下载二进制（见讲义配套说明）：

```powershell
.\qdrant.exe --config-path .\config\config.yaml
```

默认监听 `http://localhost:6333`，控制台 `http://localhost:6333/dashboard`。

### 6.2 验证

部分 Windows 二进制没有打包网页控制台，直接用 REST API 验证：

```powershell
# 查看集合
curl.exe http://localhost:6333/collections

# 创建集合（BGE-M3 是 1024 维，余弦距离）
curl.exe -X PUT http://localhost:6333/collections/video_segments -H "Content-Type: application/json" -d '{"vectors":{"size":1024,"distance":"Cosine"}}'

# 查看集合详情
curl.exe http://localhost:6333/collections/video_segments
```

跑一次分析后，`points_count` 应该大于 0；日志里应有检索命中的片段数和耗时。

## 7. 作业

### 动手任务

1. 用 Qdrant Dashboard 或 REST API 手动创建一个 collection。
2. 插入两个语义相近/不同的文本向量，验证相似度排序。
3. 停掉 Qdrant，再跑一次分析，确认任务仍能完成（降级路径）。

### 思考题

1. 向量检索相比关键词检索解决什么问题？
2. 为什么 TopK 而不是把所有片段都喂给 Agent？
3. 降级到什么程度算“可接受”？
4. Qdrant 挂了会丢业务数据吗？为什么？

## 8. 面试自测题

1. Embedding 是什么？为什么语义相近的向量距离近？
2. 向量数据库和 MySQL 的区别？
3. 混合检索是什么？为什么需要？
4. 优雅降级怎么做？举你项目里的例子。
5. TopK 的意义是什么？K 太大/太小各有什么问题？

## 9. 这一阶段在简历上怎么说

> 使用 BGE-M3 Embedding + Qdrant 构建视频片段向量检索：视频上下文按片段
> 向量化入库，用户目标语义召回 TopK 证据；Qdrant 或 Embedding 服务不可用时
> 自动降级为本地关键词匹配，保证主分析链路不中断。

等你能独立回答第 8 节全部题目，这句话才算真正属于你。
