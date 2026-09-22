package com.ipovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipovideo.dto.AnalysisTaskMsg;
import com.ipovideo.entity.TaskOutbox;
import com.ipovideo.mapper.TaskOutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TaskOutboxService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private final TaskOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    public TaskOutboxService(TaskOutboxMapper outboxMapper, ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void enqueueTask(AnalysisTaskMsg message, String topic) {
        TaskOutbox event = new TaskOutbox();
        event.setAggregateType("ANALYSIS_TASK");
        event.setAggregateId(message.taskId());
        event.setTopic(topic);
        event.setMessageKey(String.valueOf(message.taskId()));
        event.setPayload(toJson(message));
        event.setStatus(STATUS_PENDING);
        event.setRetryCount(0);
        event.setCreatedAt(LocalDateTime.now());
        event.setNextRetryAt(LocalDateTime.now());
        outboxMapper.insert(event);
    }

    public List<TaskOutbox> findDispatchable(int batchSize) {
        LambdaQueryWrapper<TaskOutbox> query = new LambdaQueryWrapper<>();
        query.eq(TaskOutbox::getStatus, STATUS_PENDING)
                .le(TaskOutbox::getNextRetryAt, LocalDateTime.now())
                .orderByAsc(TaskOutbox::getId)
                .last("LIMIT " + Math.max(1, batchSize));
        return outboxMapper.selectList(query);
    }

    public AnalysisTaskMsg payload(TaskOutbox event) throws JsonProcessingException {
        return objectMapper.readValue(event.getPayload(), AnalysisTaskMsg.class);
    }

    public void markPublished(Long id) {
        TaskOutbox event = new TaskOutbox();
        event.setId(id);
        event.setStatus(STATUS_PUBLISHED);
        event.setPublishedAt(LocalDateTime.now());
        event.setLastError(null);
        outboxMapper.updateById(event);
    }

    public void markRetry(TaskOutbox current, Exception error) {
        int retry = current.getRetryCount() == null ? 1 : current.getRetryCount() + 1;
        long delaySeconds = Math.min(60, 1L << Math.min(retry, 6));
        TaskOutbox update = new TaskOutbox();
        update.setId(current.getId());
        update.setStatus(STATUS_PENDING);
        update.setRetryCount(retry);
        update.setNextRetryAt(LocalDateTime.now().plus(Duration.ofSeconds(delaySeconds)));
        update.setLastError(truncate(error.getMessage(), 1000));
        outboxMapper.updateById(update);
    }

    private String toJson(AnalysisTaskMsg message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Outbox 消息序列化失败", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
