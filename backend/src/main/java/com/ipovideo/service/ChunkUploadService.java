package com.ipovideo.service;

import com.ipovideo.common.BusinessException;
import com.ipovideo.config.RedisKeys;
import com.ipovideo.dto.CompleteUploadRequest;
import com.ipovideo.dto.InitUploadRequest;
import com.ipovideo.dto.InitUploadResponse;
import com.ipovideo.dto.MediaView;
import com.ipovideo.dto.UploadMeta;
import com.ipovideo.entity.MediaFile;
import com.ipovideo.mapper.MediaFileMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 分片上传三段式实现：
 * init 生成 uploadId 并登记元信息；
 * part 把每个分片作为临时对象存入 MinIO（tmp/{uploadId}/{编号}）；
 * complete 校验齐了之后用 composeObject 合并成正式对象，再清理临时对象。
 */
@Service
public class ChunkUploadService {

    private static final Duration UPLOAD_TTL = Duration.ofHours(2);
    private static final Logger log = LoggerFactory.getLogger(ChunkUploadService.class);

    private final MediaFileMapper mediaFileMapper;
    private final MinioClient minioClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String bucketName;

    public ChunkUploadService(MediaFileMapper mediaFileMapper,
                              MinioClient minioClient,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper,
                              @Value("${minio.bucketName}") String bucketName) {
        this.mediaFileMapper = mediaFileMapper;
        this.minioClient = minioClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.bucketName = bucketName;
    }

    public InitUploadResponse initUpload(Long userId, InitUploadRequest request) {
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        String objectKey = "media/" + userId + "/"
                + UUID.randomUUID().toString().replace("-", "") + "_" + request.filename();
        try {
            ensureBucket();
            UploadMeta meta = new UploadMeta(
                    userId, request.filename(), request.totalParts(), objectKey, uploadId);
            redisTemplate.opsForValue().set(
                    RedisKeys.uploadMetaKey(uploadId),
                    objectMapper.writeValueAsString(meta),
                    UPLOAD_TTL);
            return new InitUploadResponse(uploadId);
        } catch (Exception ex) {
            log.error("chunk_upload_init_failed uploadId={} error={}", uploadId, ex.getMessage(), ex);
            throw new BusinessException(500, "初始化上传失败");
        }
    }

    public void uploadPart(Long userId, String uploadId, Integer partNumber, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "分片不能为空");
        }
        UploadMeta meta = loadMeta(uploadId, userId);
        String tmpKey = tmpKey(uploadId, partNumber);
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(tmpKey)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());
            redisTemplate.opsForSet().add(
                    RedisKeys.uploadPartsKey(uploadId), String.valueOf(partNumber));
        } catch (Exception ex) {
            log.error("chunk_upload_part_failed uploadId={} part={} error={}",
                    uploadId, partNumber, ex.getMessage(), ex);
            throw new BusinessException(500, "分片上传失败");
        }
    }

    public MediaView completeUpload(Long userId, CompleteUploadRequest request) {
        UploadMeta meta = loadMeta(request.uploadId(), userId);

        Set<String> parts = redisTemplate.opsForSet().members(
                RedisKeys.uploadPartsKey(request.uploadId()));
        if (parts == null || parts.size() < meta.totalParts()) {
            throw new BusinessException(400, "分片未传齐");
        }

        try {
            List<ComposeSource> sources = new ArrayList<>();
            for (int i = 1; i <= meta.totalParts(); i++) {
                sources.add(ComposeSource.builder()
                        .bucket(bucketName)
                        .object(tmpKey(request.uploadId(), i))
                        .build());
            }
            minioClient.composeObject(
                    ComposeObjectArgs.builder()
                            .bucket(bucketName)
                            .object(meta.objectKey())
                            .sources(sources)
                            .build());

            // 清理临时分片对象
            for (int i = 1; i <= meta.totalParts(); i++) {
                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(tmpKey(request.uploadId(), i))
                                .build());
            }
        } catch (Exception ex) {
            log.error("chunk_upload_complete_failed uploadId={} error={}",
                    request.uploadId(), ex.getMessage(), ex);
            throw new BusinessException(500, "合并分片失败");
        }

        MediaFile media = new MediaFile();
        media.setUserId(userId);
        media.setFilename(meta.filename());
        media.setStatus("UPLOADED");
        media.setFilePath(meta.objectKey());
        media.setUploadTime(LocalDateTime.now());
        mediaFileMapper.insert(media);

        redisTemplate.delete(RedisKeys.uploadMetaKey(request.uploadId()));
        redisTemplate.delete(RedisKeys.uploadPartsKey(request.uploadId()));
        return MediaView.from(media);
    }

    private String tmpKey(String uploadId, int partNumber) {
        return "tmp/" + uploadId + "/" + partNumber;
    }

    private UploadMeta loadMeta(String uploadId, Long userId) {
        String json = redisTemplate.opsForValue().get(RedisKeys.uploadMetaKey(uploadId));
        if (json == null) {
            throw new BusinessException(404, "上传任务不存在或已过期");
        }
        try {
            UploadMeta meta = objectMapper.readValue(json, UploadMeta.class);
            if (!meta.userId().equals(userId)) {
                throw new BusinessException(403, "无权操作该上传任务");
            }
            return meta;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "上传元信息解析失败");
        }
    }

    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }
}
