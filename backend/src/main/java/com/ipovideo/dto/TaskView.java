package com.ipovideo.dto;

import com.ipovideo.entity.AnalysisTask;

import java.time.LocalDateTime;

public record TaskView(
        Long id,
        Long mediaId,
        Long userId,
        String goal,
        String status,
        String currentStage,
        Integer progress,
        String result,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TaskView from(AnalysisTask task) {
        return new TaskView(
                task.getId(),
                task.getMediaId(),
                task.getUserId(),
                task.getGoal(),
                task.getStatus(),
                task.getCurrentStage(),
                task.getProgress(),
                task.getResult(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
