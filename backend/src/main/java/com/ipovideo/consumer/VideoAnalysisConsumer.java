package com.ipovideo.consumer;

import com.ipovideo.dto.AnalysisTaskMsg;
import com.ipovideo.dto.TaskStatus;
import com.ipovideo.entity.AnalysisTask;
import com.ipovideo.mapper.AnalysisTaskMapper;
import com.ipovideo.service.TaskWorker;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 视频分析消费者：收到消息后执行 TaskWorker。
 * 消息可能被重复投递，所以先查任务状态，已终态直接跳过（幂等消费）。
 */
@Component
@RocketMQMessageListener(
        topic = "${rocketmq.topic.video-analysis:video-analysis-topic}",
        consumerGroup = "${rocketmq.consumer.group:video-analysis-consumer}")
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    private final TaskWorker taskWorker;
    private final AnalysisTaskMapper taskMapper;

    public VideoAnalysisConsumer(TaskWorker taskWorker, AnalysisTaskMapper taskMapper) {
        this.taskWorker = taskWorker;
        this.taskMapper = taskMapper;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        if (msg == null || msg.taskId() == null) {
            // 毒消息：重投多少次都不会变好，记录日志后直接确认，避免无限重试
            log.error("video_analysis_poison_message payload={}", msg);
            return;
        }

        AnalysisTask task = taskMapper.selectById(msg.taskId());
        if (task == null) {
            // 任务已不存在（例如历史消息重放），直接确认
            log.warn("video_analysis_task_not_found taskId={}", msg.taskId());
            return;
        }

        // 幂等：重复投递时任务已经结束，直接跳过，避免重复执行

        String status = task.getStatus();
        log.info("video_analysis_received taskId={} status={}", msg.taskId(), status);
        if (TaskStatus.SUCCESS.name().equals(status) || TaskStatus.FAILED.name().equals(status)) {
            return;
        }

        String workerId = "worker-" + UUID.randomUUID().toString().replace("-", "");
        int claimed = taskMapper.claimTask(
                msg.taskId(), workerId, LocalDateTime.now().plus(LEASE_DURATION));
        if (claimed != 1) {
            log.info("video_analysis_claim_skipped taskId={}", msg.taskId());
            return;
        }

        log.info("video_analysis_claimed taskId={} workerId={}", msg.taskId(), workerId);
        taskWorker.run(msg.taskId(), workerId, LEASE_DURATION);
    }
}
