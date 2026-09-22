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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 后台分析工人：按照真实的外部操作推进任务阶段，并通过租约和心跳防止重复执行。
 */
@Component
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

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
    private final TaskScheduler taskScheduler;

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
                      @Value("${minio.bucketName:media}") String bucketName,
                      TaskScheduler taskScheduler) {
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
        this.taskScheduler = taskScheduler;
    }

    public void run(Long taskId, String workerId, Duration leaseDuration) {
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(
                () -> renewLease(taskId, workerId, leaseDuration),
                Instant.now().plusSeconds(10),
                Duration.ofSeconds(10));
        try {
            AnalysisTask task = requireTask(taskId);
            transition(taskId, workerId, leaseDuration, "PREPARE", 5, "任务开始");

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
                succeed(taskId, workerId, "DEMO", demoResult, 100);
                return;
            }

            Path workDir = Paths.get("target/ctx", String.valueOf(task.getId()));
            Files.createDirectories(workDir);

            transition(taskId, workerId, leaseDuration, "DOWNLOAD", 15, "正在下载视频");
            Path videoPath = downloadVideo(task, workDir);

            transition(taskId, workerId, leaseDuration, "BUILD_CONTEXT", 40, "正在转写语音并识别画面");
            VideoContext context = videoContextService.buildContext(videoPath, workDir);

            transition(taskId, workerId, leaseDuration, "RETRIEVE", 65, "正在检索视频证据");
            vectorStoreService.upsertSegments(task.getMediaId(), context.segments());
            List<VideoEvidenceHit> hits = vectorStoreService.search(
                    task.getGoal(), context.segments(), 5);
            String evidence = hits.isEmpty()
                    ? buildEvidenceText(context)
                    : buildHitsText(hits);

            transition(taskId, workerId, leaseDuration, "AGENT", 85, "Agent 正在生成结论");
            AgentResult agentResult = agentLoopService.run(task.getGoal(), evidence);

            transition(taskId, workerId, leaseDuration, "VALIDATE", 95, "正在校验证据引用");
            succeed(taskId, workerId, "SUCCESS", objectMapper.writeValueAsString(agentResult), 100);
        } catch (Exception ex) {
            fail(taskId, workerId, ex.getMessage());
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void transition(Long taskId,
                            String workerId,
                            Duration leaseDuration,
                            String stage,
                            int progress,
                            String message) {
        int updated = taskMapper.updateProgress(
                taskId,
                workerId,
                stage,
                progress,
                LocalDateTime.now().plus(leaseDuration));
        if (updated != 1) {
            throw new BusinessException(409, "任务租约已失效，停止重复写入");
        }
        taskEventService.publish(taskId, new TaskEvent(stage, message, progress));
    }

    private void succeed(Long taskId, String workerId, String stage, String result, int progress) {
        int updated = taskMapper.completeTask(
                taskId,
                workerId,
                TaskStatus.SUCCESS.name(),
                stage,
                progress,
                result,
                null);
        if (updated != 1) {
            throw new BusinessException(409, "任务租约已失效，无法提交结果");
        }
        taskEventService.publish(taskId, new TaskEvent(stage, "分析完成", progress));
        taskEventService.complete(taskId);
    }

    private void fail(Long taskId, String workerId, String message) {
        String safeMessage = message == null || message.isBlank() ? "未知错误" : message;
        int updated = taskMapper.completeTask(
                taskId,
                workerId,
                TaskStatus.FAILED.name(),
                "FAILED",
                0,
                null,
                safeMessage);
        if (updated == 1) {
            taskEventService.publish(taskId, new TaskEvent("FAILED", "分析失败：" + safeMessage, 0));
            taskEventService.complete(taskId);
        } else {
            log.warn("task_failure_ignored taskId={} workerId={} reason=lease-lost", taskId, workerId);
        }
    }

    private void renewLease(Long taskId, String workerId, Duration leaseDuration) {
        try {
            int updated = taskMapper.renewLease(
                    taskId, workerId, LocalDateTime.now().plus(leaseDuration));
            if (updated != 1) {
                log.warn("task_lease_renew_failed taskId={} workerId={}", taskId, workerId);
            }
        } catch (Exception ex) {
            log.warn("task_lease_renew_error taskId={} workerId={} error={}",
                    taskId, workerId, ex.getMessage());
        }
    }

    private AnalysisTask requireTask(Long taskId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        return task;
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
