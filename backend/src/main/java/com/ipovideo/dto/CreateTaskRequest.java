package com.ipovideo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotNull(message = "请选择视频")
        Long mediaId,

        @NotBlank(message = "分析目标不能为空")
        @Size(max = 500, message = "分析目标不能超过 500 字")
        String goal
) {
}
