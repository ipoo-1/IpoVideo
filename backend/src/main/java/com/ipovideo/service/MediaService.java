package com.ipovideo.service;

import com.ipovideo.common.BusinessException;
import com.ipovideo.dto.MediaView;
import com.ipovideo.entity.MediaFile;
import com.ipovideo.mapper.MediaFileMapper;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class MediaService {

    private final MediaFileMapper mediaFileMapper;
    private final MinioClient minioClient;
    private final String bucketName;

    public MediaService(MediaFileMapper mediaFileMapper,
                        MinioClient minioClient,
                        @Value("${minio.bucketName}") String bucketName) {
        this.mediaFileMapper = mediaFileMapper;
        this.minioClient = minioClient;
        this.bucketName = bucketName;
    }

    public MediaView upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的视频");
        }
        String originalName = safeFileName(file.getOriginalFilename());
        try {
            ensureBucket();

            String objectKey = userId + "/" + UUID.randomUUID().toString().replace("-", "") + "_" + originalName;
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());

            MediaFile media = new MediaFile();
            media.setUserId(userId);
            media.setFilename(originalName);
            media.setStatus("UPLOADED");
            media.setFilePath(objectKey);
            media.setContentHash(md5(file.getBytes()));
            media.setUploadTime(LocalDateTime.now());
            mediaFileMapper.insert(media);

            return MediaView.from(media);
        } catch (Exception ex) {
            throw new BusinessException(500, "文件上传失败");
        }
    }

    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }

    private String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "video.mp4";
        }
        return Paths.get(name).getFileName().toString().replace(" ", "_");
    }

    private String md5(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 不可用", ex);
        }
    }
}