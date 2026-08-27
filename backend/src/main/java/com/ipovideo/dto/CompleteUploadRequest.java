package com.ipovideo.dto;

import jakarta.validation.constraints.NotBlank;

public record CompleteUploadRequest(
        @NotBlank(message = "uploadId 不能为空") String uploadId
) {
}
