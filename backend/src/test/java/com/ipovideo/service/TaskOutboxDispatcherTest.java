package com.ipovideo.service;

import com.ipovideo.dto.AnalysisTaskMsg;
import com.ipovideo.entity.TaskOutbox;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskOutboxDispatcherTest {

    @Test
    void marksPublishedAfterSuccessfulSend() throws Exception {
        TaskOutboxService service = mock(TaskOutboxService.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        TaskOutbox event = event();
        when(service.findDispatchable(20)).thenReturn(List.of(event));
        when(service.payload(event)).thenReturn(new AnalysisTaskMsg(1L, 2L, 3L, "goal"));

        TaskOutboxDispatcher dispatcher = new TaskOutboxDispatcher(service, rocketMQTemplate, 20);
        dispatcher.dispatchPending();

        verify(rocketMQTemplate).convertAndSend(eq("video-analysis-topic"), any(AnalysisTaskMsg.class));
        verify(service).markPublished(1L);
    }

    @Test
    void keepsEventPendingWhenBrokerIsUnavailable() throws Exception {
        TaskOutboxService service = mock(TaskOutboxService.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        TaskOutbox event = event();
        when(service.findDispatchable(20)).thenReturn(List.of(event));
        when(service.payload(event)).thenReturn(new AnalysisTaskMsg(1L, 2L, 3L, "goal"));
        doThrow(new RuntimeException("broker unavailable"))
                .when(rocketMQTemplate).convertAndSend(anyString(), any(Object.class));

        TaskOutboxDispatcher dispatcher = new TaskOutboxDispatcher(service, rocketMQTemplate, 20);
        dispatcher.dispatchPending();

        verify(service).markRetry(eq(event), any(RuntimeException.class));
    }

    private TaskOutbox event() {
        TaskOutbox event = new TaskOutbox();
        event.setId(1L);
        event.setTopic("video-analysis-topic");
        event.setRetryCount(0);
        return event;
    }
}
