package com.ipovideo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipovideo.common.BusinessException;
import com.ipovideo.dto.AgentResult;
import com.ipovideo.dto.TaskEvent;
import com.ipovideo.dto.TaskStatus;
import com.ipovideo.dto.VideoContext;
import com.ipovideo.dto.VideoEvidenceHit;
import com.ipovideo.dto.VideoSegment;
import com.ipovideo.entity.AnalysisTask;
import com.ipovideo.entity.MediaFile;
import com.ipovideo.mapper.AnalysisTaskMapper;
import com.ipovideo.mapper.MediaFileMapper;
import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 后台分析工人：按照真实的外部操作推进任务阶段，并在每个阶段完成后推送 SSE。
 */
@Component
public class TaskWorker {

    private final AnalysisTaskMapper taskMapper;
    private final TaskEventService taskEventService;
    private final AgentLoopService agentLoopService;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final boolean demoModeEnabled;
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
                      @Value("${app.analysis.demo-mode-enabled:false}") boolean demoModeEnabled,
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
        this.demoModeEnabled = demoModeEnabled;
        this.mediaFileMapper = mediaFileMapper;
        this.minioClient = minioClient;
        this.vectorStoreService = vectorStoreService;
        this.videoContextService = videoContextService;
        this.bucketName = bucketName;
    }

    public void run(Long taskId) {
        try {
            AnalysisTask task = requireTask(taskId);
            transition(taskId, "PREPARE", 5, "任务开始");

            if (apiKey == null || apiKey.isBlank()) {
                if (!demoModeEnabled) {
                    throw new BusinessException(500,
                            "AI_NOT_CONFIGURED: 未配置模型密钥，无法执行真实分析");
                }
                String demoResult = objectMapper.writeValueAsString(Map.of(
                        "demo", true,
                        "title", "演示模式",
                        "conclusions", List.of(),
                        "suggestions", List.of("配置 SILICONFLOW_API_KEY 后重新提交任务")));
                succeed(taskId, "DEMO", demoResult, 100);
                return;
            }

            Path workDir = Paths.get("target/ctx", String.valueOf(task.getId()));
            Files.createDirectories(workDir);

            transition(taskId, "DOWNLOAD", 15, "正在下载视频");
            Path videoPath = downloadVideo(task, workDir);

            transition(taskId, "BUILD_CONTEXT", 40, "正在转写语音并识别画面");
            VideoContext context = videoContextService.buildContext(videoPath, workDir);

            transition(taskId, "RETRIEVE", 65, "正在检索视频证据");
            vectorStoreService.upsertSegments(task.getMediaId(), context.segments());
            List<VideoEvidenceHit> hits = vectorStoreService.search(
                    task.getGoal(), context.segments(), 5);
            String evidence = hits.isEmpty()
                    ? buildEvidenceText(context)
                    : buildHitsText(hits);

            transition(taskId, "AGENT", 85, "Agent 正在生成结论");
            AgentResult agentResult = agentLoopService.run(task.getGoal(), evidence);

            transition(taskId, "VALIDATE", 95, "正在校验证据引用");
            succeed(taskId, "SUCCESS", objectMapper.writeValueAsString(agentResult), 100);
        } catch (Exception ex) {
            fail(taskId, ex.getMessage());
        }
    }

    private void transition(Long taskId, String stage, int progress, String message) {
        update(taskId, task -> {
            task.setStatus(TaskStatus.RUNNING.name());
            task.setCurrentStage(stage);
            task.setProgress(progress);
        });
        taskEventService.publish(taskId, new TaskEvent(stage, message, progress));
    }

    private void succeed(Long taskId, String stage, String result, int progress) {
        update(taskId, task -> {
            task.setStatus(TaskStatus.SUCCESS.name());
            task.setCurrentStage(stage);
            task.setProgress(progress);
            task.setResult(result);
            task.setErrorMessage(null);
        });
        taskEventService.publish(taskId, new TaskEvent(stage, "分析完成", progress));
        taskEventService.complete(taskId);
    }

    private void fail(Long taskId, String message) {
        String safeMessage = message == null || message.isBlank() ? "未知错误" : message;
        update(taskId, task -> {
            task.setStatus(TaskStatus.FAILED.name());
            task.setErrorMessage(safeMessage);
        });
        taskEventService.publish(taskId, new TaskEvent("FAILED", "分析失败：" + safeMessage, 0));
        taskEventService.complete(taskId);
    }

    private AnalysisTask requireTask(Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        return task;
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

    private Path downloadVideo(AnalysisTask task, Path workDir) throws Exception {
        MediaFile media = mediaFileMapper.selectById(task.getMediaId());
        if (media == null) {
            throw new IllegalStateException("视频不存在: " + task.getMediaId());
        }
        Path videoPath = workDir.resolve("video.mp4");
        minioClient.downloadObject(
                DownloadObjectArgs.builder()
                        .bucket(bucketName)
                        .object(media.getFilePath())
                        .filename(videoPath.toString())
                        .build());
        return videoPath;
    }

    private String buildHitsText(List<VideoEvidenceHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < hits.size(); index++) {
            VideoEvidenceHit hit = hits.get(index);
            sb.append("[E").append(index + 1).append("] ")
                    .append("[ ").append(hit.startMs()).append("ms - ")
                    .append(hit.endMs()).append("ms] ")
                    .append(hit.text()).append('\n');
        }
        return sb.toString();
    }

    private String buildEvidenceText(VideoContext context) {
        StringBuilder sb = new StringBuilder();
        int evidenceNumber = 1;
        for (VideoSegment segment : context.segments()) {
            if (segment.asrText() != null && !segment.asrText().isBlank()) {
                sb.append("[E").append(evidenceNumber++).append("] [语音 ")
                        .append(segment.startMs()).append("ms] ")
                        .append(segment.asrText()).append('\n');
            }
            if (segment.ocrText() != null && !segment.ocrText().isBlank()) {
                sb.append("[E").append(evidenceNumber++).append("] [画面 ")
                        .append(segment.startMs()).append("ms] ")
                        .append(segment.ocrText()).append('\n');
            }
        }
        return sb.toString();
    }
}
