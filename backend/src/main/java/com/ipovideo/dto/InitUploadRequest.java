package com.ipovideo.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InitUploadRequest(
        @NotBlank(message = "文件名不能为空") String filename,

        @NotNull(message = "分片总数不能为空")
        @Min(value = 1, message = "分片总数至少为 1")
        Integer totalParts
) {
}
