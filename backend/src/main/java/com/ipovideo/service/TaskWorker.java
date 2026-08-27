package com.ipovideo.service;

import com.ipovideo.dto.AgentResult;
import com.ipovideo.dto.TaskEvent;
import com.ipovideo.dto.TaskStage;
import com.ipovideo.dto.TaskStatus;
import com.ipovideo.dto.VideoEvidenceHit;
import com.ipovideo.dto.VideoContext;
import com.ipovideo.dto.VideoSegment;
import com.ipovideo.entity.AnalysisTask;
import com.ipovideo.entity.MediaFile;
import com.ipovideo.mapper.AnalysisTaskMapper;
import com.ipovideo.mapper.MediaFileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;

/**
 * 后台分析工人：模拟真实的视频分析流程，逐阶段更新任务状态并推送 SSE。
 * Stage 7 会把这里的“模拟分析”替换成真正的 AI Agent。
 */
@Component
public class TaskWorker {

    private static final List<TaskStage> STAGES = List.of(
            new TaskStage("TRANSCRIBE", "正在转写语音", 20),
            new TaskStage("EXTRACT_FRAMES", "正在抽取关键帧", 45),
            new TaskStage("RETRIEVE", "正在检索相关片段", 70),
            new TaskStage("AGENT", "Agent 正在生成结论", 90)
    );

    private final AnalysisTaskMapper taskMapper;
    private final TaskEventService taskEventService;
    private final AgentLoopService agentLoopService;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final MediaFileMapper mediaFileMapper;
    private final MinioClient minioClient;
    private final VideoContextService videoContextService;
    private final String bucketName;
    private final VectorStoreService vectorStoreService;

    public TaskWorker(AnalysisTaskMapper taskMapper,
                      TaskEventService taskEventService,
                      AgentLoopService agentLoopService,
                      ObjectMapper objectMapper,
                      @Value("${ai.deepseek.api-key:}") String apiKey,
                      MediaFileMapper mediaFileMapper,
                      MinioClient minioClient,
                      VectorStoreService vectorStoreService,
                      VideoContextService videoContextService,
                      @Value("${minio.bucketName:media}") String bucketName) {
        this.taskMapper = taskMapper;
        this.taskEventService = taskEventService;
        this.agentLoopService = agentLoopService;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.mediaFileMapper = mediaFileMapper;
        this.minioClient = minioClient;
        this.vectorStoreService = vectorStoreService;
        this.videoContextService = videoContextService;
        this.bucketName = bucketName;
    }

    public void run(Long taskId) {
        try {
            update(taskId, task -> {
                task.setStatus(TaskStatus.RUNNING.name());
                task.setCurrentStage("PREPARE");
                task.setProgress(5);
            });
            taskEventService.publish(taskId, new TaskEvent("PREPARE", "任务开始", 5));

            for (TaskStage stage : STAGES) {
                Thread.sleep(300);
                update(taskId, task -> {
                    task.setStatus(TaskStatus.RUNNING.name());
                    task.setCurrentStage(stage.name());
                    task.setProgress(stage.progress());
                });
                taskEventService.publish(taskId, new TaskEvent(stage.name(), stage.message(), stage.progress()));
            }

            AnalysisTask analysisTask = taskMapper.selectById(taskId);
            String evidence = "";
            if (apiKey != null && !apiKey.isBlank()) {
                evidence = buildEvidence(analysisTask);
            }
            String resultText = resolveResult(analysisTask, evidence);
            update(taskId, task -> {
                task.setStatus(TaskStatus.SUCCESS.name());
                task.setProgress(100);
                task.setResult(resultText);
            });
            taskEventService.publish(taskId, new TaskEvent("SUCCESS", "分析完成", 100));
            taskEventService.complete(taskId);
        } catch (Exception ex) {
            update(taskId, task -> {
                task.setStatus(TaskStatus.FAILED.name());
                task.setErrorMessage(ex.getMessage());
            });
            taskEventService.publish(taskId, new TaskEvent("FAILED", "分析失败：" + ex.getMessage(), 0));
            taskEventService.complete(taskId);
        }
    }

    private void update(Long taskId, Consumer<AnalysisTask> change) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        change.accept(task);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    /**
     * 没配置 API Key 时用模拟结果，保证本地开发和测试不受影响；
     * 配置了 Key 就调用真正的 Planner-Executor-Critic 工作流。
     */
    private String resolveResult(AnalysisTask task, String evidence) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            return "分析完成：已提取视频核心知识点、时间戳证据与复习建议。（未配置 AI Key，使用模拟结果）";
        }
        AgentResult agentResult = agentLoopService.run(task.getGoal(), evidence);
        return objectMapper.writeValueAsString(agentResult);
    }

    /**
     * 从 MinIO 下载视频，构建 VideoContext，拼成给模型的证据文本。
     */
    private String buildEvidence(AnalysisTask task) throws Exception {
        MediaFile media = mediaFileMapper.selectById(task.getMediaId());
        if (media == null) {
            throw new IllegalStateException("视频不存在: " + task.getMediaId());
        }
        Path workDir = Paths.get("target/ctx", String.valueOf(task.getId()));
        Files.createDirectories(workDir);
        Path videoPath = workDir.resolve("video.mp4");
        minioClient.downloadObject(
                DownloadObjectArgs.builder()
                        .bucket(bucketName)
                        .object(media.getFilePath())
                        .filename(videoPath.toString())
                        .build());

        VideoContext context = videoContextService.buildContext(videoPath, workDir);
        vectorStoreService.upsertSegments(task.getMediaId(), context.segments());
        List<VideoEvidenceHit> hits = vectorStoreService.search(
                task.getGoal(), context.segments(), 5);
        if (hits.isEmpty()) {
            // 召回为空就退回全部片段，保证 Agent 有证据可用
            return buildEvidenceText(context);
        }
        return buildHitsText(hits);
    }

    private String buildHitsText(List<VideoEvidenceHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (VideoEvidenceHit hit : hits) {
            sb.append("[证据 ").append(hit.startMs()).append("ms] ").append(hit.text()).append('\n');
        }
        return sb.toString();
    }

    private String buildEvidenceText(VideoContext context) {
        StringBuilder sb = new StringBuilder();
        for (VideoSegment segment : context.segments()) {
            if (segment.asrText() != null && !segment.asrText().isBlank()) {
                sb.append("[语音] ").append(segment.asrText()).append('\n');
            }
            if (segment.ocrText() != null && !segment.ocrText().isBlank()) {
                sb.append("[画面] ").append(segment.ocrText()).append('\n');
            }
        }
        return sb.toString();
    }
}
