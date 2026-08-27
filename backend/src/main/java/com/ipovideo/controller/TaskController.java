package com.ipovideo.controller;

import com.ipovideo.common.Result;
import com.ipovideo.config.AuthInterceptor;
import com.ipovideo.dto.CreateTaskRequest;
import com.ipovideo.dto.TaskView;
import com.ipovideo.service.TaskEventService;
import com.ipovideo.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskEventService taskEventService;

    public TaskController(TaskService taskService, TaskEventService taskEventService) {
        this.taskService = taskService;
        this.taskEventService = taskEventService;
    }

    @PostMapping
    public Result<TaskView> create(@Valid @RequestBody CreateTaskRequest request,
                                   HttpServletRequest http) {
        Long userId = (Long) http.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        return Result.ok(taskService.create(userId, request));
    }

    @GetMapping("/{id}")
    public Result<TaskView> get(@PathVariable Long id, HttpServletRequest http) {
        Long userId = (Long) http.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        return Result.ok(taskService.getForUser(id, userId));
    }

    @GetMapping("/{id}/events")
    public SseEmitter events(@PathVariable Long id, HttpServletRequest http) {
        Long userId = (Long) http.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        taskService.checkAccess(id, userId);
        return taskEventService.register(id);
    }

    @GetMapping
    public Result<List<TaskView>> list(HttpServletRequest http) {
        Long userId = (Long) http.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        return Result.ok(taskService.listForUser(userId));
    }
}
