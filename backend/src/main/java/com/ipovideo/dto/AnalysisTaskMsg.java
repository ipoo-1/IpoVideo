package com.ipovideo.dto;

/**
 * RocketMQ 消息体：告诉消费者“去执行哪个任务”。
 */
public record AnalysisTaskMsg(
        Long taskId,
        Long mediaId,
        Long userId,
        String goal
) {
}
