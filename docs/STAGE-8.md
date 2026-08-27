# Stage 8：FFmpeg + ASR + OCR 构建 VideoContext

## 1. 本阶段目标

让 Agent 真正“看视频”：把视频变成带时间轴的**多模态上下文**：

1. FFmpeg 从视频里抽出音频，切成 60 秒一段，交给 ASR 转成语音文字。
2. FFmpeg 抽取关键帧，Tesseract 做 OCR，得到画面文字。
3. 按时间轴合并成 `VideoSegment`，组成 `VideoContext`，作为 Agent 的证据。

## 2. 先建立一张地图

```text
原始视频
    │
    ├── 音频分支：FFmpeg 去画面抽 MP3 -> 按 60 秒切段 -> ASR 转文字
    │
    └── 画面分支：FFmpeg 场景变化检测 + 30 秒保底抽帧 -> Tesseract OCR
                          │
                          ▼
        按 60 秒时间窗口合并
        VideoSegment{ start, end, asrText, ocrText, frames }
                          │
                          ▼
                    VideoContext（整条时间轴）
                          │
                          ▼
                  Agent 拿它当证据生成结论
```

两条分支**并行执行、互相容错**：ASR 挂了画面文字还在，OCR 挂了语音还在。

## 3. 核心概念

### 3.1 FFmpeg 是什么

FFmpeg 是命令行多媒体处理工具，本阶段用两个能力：

```bash
# 抽音频（去掉画面，编码成 mp3）
ffmpeg -i video.mp4 -vn -acodec libmp3lame audio.mp3

# 抽关键帧（按场景变化选帧，每 30 秒保底一帧）
ffmpeg -i video.mp4 -vf "select='gt(scene,0.35)+eq(n,0)',setpts=N/FRAME_RATE/TB" frame-%03d.jpg
```

### 3.2 ASR：语音转文字

把音频段发给 SiliconFlow 的语音识别接口（`/audio/transcriptions`，复用同一个 API Key），返回这段语音的文字。每个 60 秒段对应一个时间戳范围。

### 3.3 OCR：画面文字识别

关键帧图片交给 Tesseract（`-l chi_sim+eng`），识别 PPT、板书、屏幕上的文字。OCR 失败只丢这一帧，不影响整条链路。

### 3.4 VideoSegment / VideoContext

统一的时间轴结构：

```java
record VideoSegment(
        long startMs,      // 开始毫秒
        long endMs,        // 结束毫秒
        String asrText,    // 语音文字（可能为空）
        String ocrText,    // 画面文字（可能为空）
        List<String> frames // 关键帧引用
) {}
```

`VideoContext` 就是按顺序排列的 `List<VideoSegment>`。后续检索、Planner、Executor 只认这一种结构，不关心底层是 ASR 还是 OCR。

### 3.5 为什么按 60 秒切片

- 切太短：调用 ASR 次数暴增，浪费网络和额度。
- 切太长：证据定位太粗，用户没法跳到具体位置。
- 60 秒是“调用次数”和“定位精度”的折中（参考原项目）。

### 3.6 容错设计

视频处理最容易失败：命令不存在、格式不支持、接口超时。设计原则：

- 单段失败不拖垮整条链路（保留其他成功段）。
- 两路分支都失败才判定整个上下文构建失败。
- 临时文件（音频、关键帧）用完即删。

## 4. 代码地图（本阶段新增/改动）

| 文件 | 作用 |
| :--- | :--- |
| `utils/FfmpegUtils.java` | 封装抽音频、切段、抽关键帧 |
| `utils/OcrUtils.java` | 封装 Tesseract OCR |
| `utils/AudioTranscriptionUtils.java` | 调用 SiliconFlow ASR |
| `dto/VideoSegment.java` | 时间轴片段 |
| `dto/VideoContext.java` | 多模态上下文 |
| `service/VideoContextService.java` | 编排两条分支并合并 |
| `service/TaskWorker.java` | 构建上下文后传给 Agent |

## 5. 一次分析的完整旅程（Stage 8 版）

1. 任务到达消费者，TaskWorker 开始执行。
2. 从 MinIO 下载视频到本地临时目录。
3. 并行启动音频分支和画面分支。
4. 音频分支：FFmpeg 抽 MP3 -> 切 60 秒段 -> 每段 ASR。
5. 画面分支：FFmpeg 抽关键帧 -> 每帧 OCR。
6. 按时间窗口合并成 VideoContext，存 MySQL/Redis（Checkpoint）。
7. AgentLoop 的 prompt 里带上 VideoContext 片段，结论绑定时间戳证据。
8. 清理临时文件，任务落 SUCCESS。

## 6. 运行与验证

### 6.1 准备一个真实短视频

用 FFmpeg 生成带语音的测试视频，或用手机拍一小段含 PPT/字幕的视频，上传到项目。

### 6.2 跑一次任务

上传 -> 创建任务 -> 轮询结果，然后查 MySQL：

```sql
SELECT id, status, LEFT(result, 500) FROM analysis_tasks ORDER BY id DESC LIMIT 3;
```

结果里的结论应能引用到具体时间戳（Stage 8 目标是“上下文里有时间戳”，Agent 引用它们）。

### 6.3 直接验证 VideoContext

在日志或临时输出中查看生成的 `VideoSegment`：每段有 start/end、asrText、ocrText。

## 7. 作业

### 动手任务

1. 用 FFmpeg 手工对一个小视频执行“抽音频 + 抽关键帧”，观察输出文件。
2. 对一张截图手工执行 `tesseract xxx.png stdout -l chi_sim+eng`，确认中文识别。
3. 上传视频跑一次完整分析，观察日志里 ASR/OCR 两条分支的执行顺序和耗时。

### 思考题

1. 为什么 ASR 和 OCR 要并行？串行会怎样？
2. 为什么单段失败不能拖垮整条链路？
3. VideoContext 为什么只保留结构化字段，不保留原始音频/图片？
4. 60 秒窗口太大或太小各有什么代价？

## 8. 面试自测题

1. FFmpeg 在项目里承担什么？
2. ASR 和 OCR 分别解决什么问题？为什么缺一不可？
3. VideoSegment 结构解决了什么问题？
4. 多模态上下文为什么按时间轴组织？
5. 处理失败时怎么保证主链路不崩？

## 9. 这一阶段在简历上怎么说

> 使用 FFmpeg + SiliconFlow ASR + Tesseract OCR 构建视频多模态上下文
> VideoContext：音频按 60 秒切片转写、关键帧场景检测与保底抽取并 OCR，
> 两条分支并行且互相容错，按统一时间轴合并为 VideoSegment，供 Agent 作为
> 可追溯证据使用。

等你能独立回答第 8 节全部题目，这句话才算真正属于你。
