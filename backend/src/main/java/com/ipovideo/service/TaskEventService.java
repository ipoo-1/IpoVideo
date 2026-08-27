package com.ipovideo.service;

import com.ipovideo.dto.TaskEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 事件中心：维护每个任务的连接，后台任务通过它推送进度。
 * 本阶段用内存保存连接，Stage 4 可以升级为 Redis Pub/Sub 支撑多实例。
 */
@Service
public class TaskEventService {

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long taskId) {
        SseEmitter emitter = new SseEmitter(60_000L);
        emitters.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(error -> remove(taskId, emitter));
        return emitter;
    }

    public void publish(Long taskId, TaskEvent event) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("task").data(event));
            } catch (IOException | IllegalStateException ex) {
                remove(taskId, emitter);
            }
        }
    }

    public void complete(Long taskId) {
        List<SseEmitter> list = emitters.remove(taskId);
        if (list == null) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 连接可能已经断开
            }
        }
    }

    private void remove(Long taskId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
        }
    }
}
