package com.ipovideo.service;

import com.ipovideo.entity.TaskOutbox;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskOutboxDispatcher.class);

    private final TaskOutboxService outboxService;
    private final RocketMQTemplate rocketMQTemplate;
    private final int batchSize;

    public TaskOutboxDispatcher(TaskOutboxService outboxService,
                                RocketMQTemplate rocketMQTemplate,
                                @Value("${app.outbox.batch-size:20}") int batchSize) {
        this.outboxService = outboxService;
        this.rocketMQTemplate = rocketMQTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms:1000}")
    public void dispatchPending() {
        for (TaskOutbox event : outboxService.findDispatchable(batchSize)) {
            try {
                rocketMQTemplate.convertAndSend(event.getTopic(), outboxService.payload(event));
                outboxService.markPublished(event.getId());
            } catch (Exception ex) {
                log.warn("outbox_publish_failed eventId={} retry={} error={}",
                        event.getId(), event.getRetryCount(), ex.getMessage());
                outboxService.markRetry(event, ex);
            }
        }
    }
}
