package com.ipovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ipovideo.dto.AnalysisTaskMsg;
import com.ipovideo.dto.TaskStatus;
import com.ipovideo.entity.AnalysisTask;
import com.ipovideo.mapper.AnalysisTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class TaskRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryScheduler.class);

    private final AnalysisTaskMapper taskMapper;
    private final TaskOutboxService outboxService;
    private final String analysisTopic;
    private final int batchSize;

    public TaskRecoveryScheduler(AnalysisTaskMapper taskMapper,
                                 TaskOutboxService outboxService,
                                 @Value("${rocketmq.topic.video-analysis:video-analysis-topic}") String analysisTopic,
                                 @Value("${app.recovery.batch-size:20}") int batchSize) {
        this.taskMapper = taskMapper;
        this.outboxService = outboxService;
        this.analysisTopic = analysisTopic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.recovery.fixed-delay-ms:30000}")
    @Transactional
    public void recoverExpiredTasks() {
        List<AnalysisTask> expired = taskMapper.selectList(
                new LambdaQueryWrapper<AnalysisTask>()
                        .eq(AnalysisTask::getStatus, TaskStatus.RUNNING.name())
                        .lt(AnalysisTask::getLeaseUntil, LocalDateTime.now())
                        .orderByAsc(AnalysisTask::getId)
                        .last("LIMIT " + Math.max(1, batchSize)));

        for (AnalysisTask task : expired) {
            int requeued = taskMapper.requeueExpiredTask(task.getId());
            if (requeued == 1) {
                outboxService.enqueueTask(
                        new AnalysisTaskMsg(
                                task.getId(), task.getMediaId(), task.getUserId(), task.getGoal()),
                        analysisTopic);
                log.warn("task_requeued_after_lease_expired taskId={} workerId={}",
                        task.getId(), task.getWorkerId());
            }
        }
    }
}
