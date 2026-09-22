package com.ipovideo.service;

import com.ipovideo.dto.AnalysisTaskMsg;
import com.ipovideo.dto.TaskStatus;
import com.ipovideo.entity.AnalysisTask;
import com.ipovideo.mapper.AnalysisTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRecoverySchedulerTest {

    @Test
    void requeuesExpiredTaskIntoOutbox() {
        AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
        TaskOutboxService outbox = mock(TaskOutboxService.class);
        AnalysisTask task = task();
        when(mapper.selectList(any())).thenReturn(List.of(task));
        when(mapper.requeueExpiredTask(1L)).thenReturn(1);

        TaskRecoveryScheduler scheduler =
                new TaskRecoveryScheduler(mapper, outbox, "video-analysis-topic", 20);
        scheduler.recoverExpiredTasks();

        ArgumentCaptor<AnalysisTaskMsg> message = ArgumentCaptor.forClass(AnalysisTaskMsg.class);
        verify(outbox).enqueueTask(message.capture(), anyString());
        assertEquals(1L, message.getValue().taskId());
    }

    @Test
    void doesNotCreateDuplicateRecoveryEventWhenTaskWasAlreadyClaimed() {
        AnalysisTaskMapper mapper = mock(AnalysisTaskMapper.class);
        TaskOutboxService outbox = mock(TaskOutboxService.class);
        when(mapper.selectList(any())).thenReturn(List.of(task()));
        when(mapper.requeueExpiredTask(1L)).thenReturn(0);

        TaskRecoveryScheduler scheduler =
                new TaskRecoveryScheduler(mapper, outbox, "video-analysis-topic", 20);
        scheduler.recoverExpiredTasks();

        verify(outbox, never()).enqueueTask(any(), anyString());
    }

    private AnalysisTask task() {
        AnalysisTask task = new AnalysisTask();
        task.setId(1L);
        task.setMediaId(2L);
        task.setUserId(3L);
        task.setGoal("goal");
        task.setStatus(TaskStatus.RUNNING.name());
        task.setWorkerId("dead-worker");
        task.setLeaseUntil(LocalDateTime.now().minusMinutes(5));
        task.setAttemptCount(1);
        return task;
    }
}
