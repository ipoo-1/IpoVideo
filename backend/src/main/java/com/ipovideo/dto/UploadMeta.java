package com.ipovideo.dto;

/**
 * 一次分片上传的元信息，序列化成 JSON 存在 Redis。
 */
public record UploadMeta(
        Long userId,
        String filename,
        Integer totalParts,
        String objectKey,
        String minioUploadId
) {
}
