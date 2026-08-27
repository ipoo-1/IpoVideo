# Stage 6：MinIO 对象存储 + 分片上传

## 1. 本阶段目标

把视频文件从“本地磁盘”升级到“MinIO 对象存储”，并实现大文件分片上传与断点续传：

1. 文件不再存服务器磁盘，而是存 MinIO；MySQL 只存文件元数据。
2. 大视频拆成 5MB 左右的分片上传，中断后只重传缺失分片。
3. 上传完成通过 MD5 校验，保证文件完整。

## 2. 先建立一张地图

```text
Stage 3（本地磁盘）：
浏览器 -> 服务器磁盘 uploads/xxx.mp4
         └ 单点、容量有限、部署多台时文件不共享

Stage 6（MinIO 对象存储）：
浏览器 -> MinIO bucket（对象存储）
          └ 独立服务，多台后端共享，容量可扩展

MySQL 只存：
  media_files 表：文件名、bucket、objectKey、MD5、大小
```

## 3. 核心概念

### 3.1 为什么文件不能进 MySQL

- MySQL 擅长“结构化小数据”，不擅长存大文件。
- 文件进 MySQL 会让数据库爆炸式变大、备份变慢、查询变慢。
- 正确分工：**数据库存“这张表”，对象存储存“这个文件”**。

### 3.2 MinIO 与 S3

MinIO 是兼容 S3 协议的开源对象存储，核心概念：

| 概念 | 类比 |
| :--- | :--- |
| Bucket 桶 | 硬盘里的一个分区/文件夹 |
| Object 对象 | 一个文件（包含数据 + 元数据） |
| Endpoint 地址 | 访问 MinIO 的网址，如 `http://localhost:9000` |
| AccessKey/SecretKey | 登录凭证 |

### 3.3 分片上传与断点续传

大文件（比如 2GB 视频）一次上传失败就得重来，所以要拆：

```text
1. 初始化上传：告诉 MinIO "我要传这个文件"，拿到 uploadId
2. 分片上传：把文件切成 N 片，一片一片传，每片带分片编号
3. 完成合并：告诉 MinIO "传完了"，它把分片拼成完整文件
```

断点续传原理：Redis 记录“哪些分片已经传成功”。
网络断了，前端重新问一次“缺哪几片”，只补缺失的部分，不重传整个文件。

### 3.4 MD5 校验

分片上传完成后，后端把合并后的文件再算一次 MD5，
和前端/Redis 记录里的值比对，不一致就判定上传失败，防止文件损坏。

## 4. 代码地图（本阶段新增/改动）

| 文件 | 作用 |
| :--- | :--- |
| `pom.xml` | 新增 MinIO Java SDK |
| `application.properties` | MinIO endpoint、账号、bucket |
| `config/MinioConfig.java` | 创建 MinioClient Bean |
| `service/MediaService.java` | 本地保存改为 MinIO 保存 |
| `service/ChunkUploadService.java` | 初始化/传分片/合并三段逻辑 |
| `controller/MediaController.java` | 分片上传三个接口 |
| `RedisKeys.java` | 新增分片记录 key |

## 5. 一次分片上传的完整旅程

1. 前端把 2GB 视频切成 5MB 的分片，调用 `POST /api/media/upload/init`。
2. 后端生成 uploadId，在 Redis 记录归属和分片总数。
3. 前端逐个调用 `POST /api/media/upload/part`，传分片编号 + 数据。
4. 每个分片成功写入 MinIO，Redis 标记该分片已完成。
5. 全部传完，前端调 `POST /api/media/upload/complete`。
6. 后端从 MinIO 合并分片成完整文件，算 MD5，写 `media_files` 表。
7. 前端拿到 mediaId，之后创建分析任务。

## 6. 运行与验证

### 6.1 启动 MinIO（Windows 二进制版，不需要 Docker）

下载：

```text
https://dl.min.io/server/minio/release/windows-amd64/minio.exe
```

启动：

```powershell
cd D:\minio
.\minio.exe server D:\minio-data --console-address :9001
```

浏览器打开 `http://localhost:9001`，默认账号密码：

```text
minioadmin / minioadmin
```

### 6.2 配置后端

在 `application.properties` 配置：

```properties
minio.endpoint=http://localhost:9000
minio.accessKey=minioadmin
minio.secretKey=minioadmin
minio.bucketName=media
```

### 6.3 验证

用脚本或 curl 走一遍“初始化 -> 传分片 -> 合并”，
然后到 MinIO 控制台确认 `media` bucket 里出现了完整文件。

## 7. 作业

### 动手任务

1. 用浏览器打开 MinIO 控制台，手动创建一个 bucket，上传一个小文件，再下载回来。
2. 把视频切成 3 片模拟断点：只传第 1、3 片，用 `complete` 触发错误；补传第 2 片后再完成。
3. 查看 `media_files` 表，确认存储的是 bucket 和 objectKey，而不是文件二进制。

### 思考题

1. 为什么大文件要分片？一次传完有什么风险？
2. Redis 记录分片进度，Redis 丢了会怎样？会影响已传文件吗？
3. 为什么 MySQL 里不存文件本身？
4. 多台后端部署时，本地磁盘存储有什么问题？MinIO 怎么解决？

## 8. 面试自测题

1. 对象存储和服务器本地磁盘有什么区别？
2. Bucket、Object、Endpoint 分别是什么？
3. 分片上传的三段流程是什么？
4. 断点续传的原理是什么？
5. 为什么文件不进 MySQL？
6. MD5 在上传流程里起什么作用？

## 9. 这一阶段在简历上怎么说

> 使用 MinIO 对象存储替代本地磁盘保存视频，实现初始化、分片上传、合并三段式
> 大文件上传链路，结合 Redis 记录分片进度支持断点续传，并以 MD5 校验文件完整性；
> 数据库仅保存文件元数据。

等你能独立回答第 8 节全部题目，这句话才算真正属于你。
