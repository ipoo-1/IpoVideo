package com.ipovideo.dto;

import com.ipovideo.entity.MediaFile;

import java.time.LocalDateTime;

public record MediaView(
        Long id,
        String filename,
        String status,
        String filePath,
        String contentHash,
        LocalDateTime uploadTime
) {
    public static MediaView from(MediaFile media) {
        return new MediaView(
                media.getId(),
                media.getFilename(),
                media.getStatus(),
                media.getFilePath(),
                media.getContentHash(),
                media.getUploadTime()
        );
    }
}
