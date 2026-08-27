# Stage 10：工程化（测试、Docker、CI、部署）

## 1. 本阶段目标

把“能跑的代码”变成“能交付的项目”：

1. Dockerfile + docker-compose：一条命令起全部中间件和后端。
2. CI：每次提交自动跑测试，坏代码进不了主干。
3. 环境变量管理：密钥不进代码仓库。
4. 部署：从“本地能跑”到“服务器能跑”。

## 2. 先建立一张地图

```text
开发机                            CI（GitHub Actions）
  docker compose up        ->     拉代码 -> 起服务 -> mvn test
  MySQL/Redis/MinIO/Qdrant
  RocketMQ（二进制）
  后端（本地 mvnw 启动）
                                    │ 通过
                                    ▼
                              构建 jar -> 推镜像
                                    ▼
                              服务器 docker compose 启动
```

## 3. 核心概念

### 3.1 Dockerfile vs docker-compose

| | Dockerfile | docker-compose.yml |
| :--- | :--- | :--- |
| 描述什么 | 一个镜像怎么构建 | 一组服务怎么编排 |
| 类比 | 一个人的简历 | 一个团队的组织架构 |

### 3.2 镜像分层

Dockerfile 每一行产生一层。合理顺序（先依赖后代码）可以复用缓存，改代码不用重新下载依赖。

### 3.3 CI 是什么

Continuous Integration：每次 push 自动执行构建和测试。我们的目标是 `mvn test` 全绿才允许合并。

### 3.4 环境变量与密钥

- 密钥通过环境变量注入，绝不写进代码/镜像。
- `.env.example` 提交仓库，真实 `.env` 只在本地。
- 本项目已遵守：`SILICONFLOW_API_KEY` 只在运行时注入。

### 3.5 健康检查与依赖顺序

中间件启动有先后（MySQL 要先就绪），compose 用 `depends_on` + 健康检查保证顺序，而不是简单 sleep。

## 4. 代码地图（本阶段新增）

| 文件 | 作用 |
| :--- | :--- |
| `backend/Dockerfile` | 后端镜像：构建 jar + 运行 |
| `docker-compose.yml` | MySQL/Redis/MinIO/Qdrant + 后端编排 |
| `docker-compose.ci.yml` | CI 专用：再叠加 RocketMQ |
| `.github/workflows/ci.yml` | 每次 push 自动测试 |
| `.env.example` | 环境变量模板 |

## 5. 一次 CI 的完整旅程

1. 你 push 代码到 GitHub。
2. GitHub Actions 启动 Ubuntu 虚拟机。
3. 用 docker compose 起 Redis、RocketMQ、MinIO、Qdrant。
4. 运行 `mvn test`（H2 内存库 + 真实中间件）。
5. 全绿 -> 构建 jar -> 结束；失败 -> 任务标红，你得修。

## 6. 运行与验证

### 6.1 本地一键起数据中间件

```powershell
cd D:\No1\DOVideo-AI-main\rebuild
docker compose up -d
```

### 6.2 起后端

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

### 6.3 触发 CI

把项目推到 GitHub，看 Actions 页面有没有绿勾。

## 7. 作业

### 动手任务

1. 读懂 Dockerfile 每一行，说出每一层在干什么。
2. 本地（有 Docker 后）跑 `docker compose up -d`，验证 4 个中间件健康。
3. 给项目建 GitHub 仓库并推送，观察 CI 第一次跑的结果。

### 思考题

1. Dockerfile 里为什么先 COPY pom 再 COPY src？
2. 密钥为什么不能写进 Dockerfile？
3. CI 全绿意味着代码没问题吗？
4. depends_on 和健康检查有什么区别？

## 8. 面试自测题

1. Dockerfile 和 docker-compose 的区别？
2. 镜像分层带来什么好处？
3. CI 解决什么问题？
4. 环境变量怎么管理密钥？
5. 怎么保证中间件启动顺序？

## 9. 这一阶段在简历上怎么说

> 完成项目工程化：编写 Dockerfile 与 docker-compose 编排 MySQL/Redis/MinIO/Qdrant/
> RocketMQ 与后端服务；接入 GitHub Actions 自动构建与测试；密钥通过环境变量注入，
> 保证开发、测试、生产环境配置可移植。

等你能独立回答第 8 节全部题目，这句话才算真正属于你。
