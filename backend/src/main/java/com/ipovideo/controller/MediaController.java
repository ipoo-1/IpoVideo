package com.ipovideo.controller;

import com.ipovideo.common.Result;
import com.ipovideo.config.AuthInterceptor;
import com.ipovideo.dto.CompleteUploadRequest;
import com.ipovideo.dto.InitUploadRequest;
import com.ipovideo.dto.InitUploadResponse;
import com.ipovideo.dto.MediaView;
import com.ipovideo.service.ChunkUploadService;
import com.ipovideo.service.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final MediaService mediaService;
    private final ChunkUploadService chunkUploadService;

    public MediaController(MediaService mediaService, ChunkUploadService chunkUploadService) {
        this.mediaService = mediaService;
        this.chunkUploadService = chunkUploadService;
    }

    @PostMapping("/upload")
    public Result<MediaView> upload(HttpServletRequest request,
                                    @RequestParam("file") MultipartFile file) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        return Result.ok(mediaService.upload(userId, file));
    }

    @PostMapping("/upload/init")
    public Result<InitUploadResponse> initUpload(HttpServletRequest request,
                                                 @Valid @RequestBody InitUploadRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        return Result.ok(chunkUploadService.initUpload(userId, body));
    }

    @PostMapping("/upload/part")
    public Result<Void> uploadPart(HttpServletRequest request,
                                   @RequestParam("uploadId") String uploadId,
                                   @RequestParam("partNumber") Integer partNumber,
                                   @RequestParam("file") MultipartFile file) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        chunkUploadService.uploadPart(userId, uploadId, partNumber, file);
        return Result.ok();
    }

    @PostMapping("/upload/complete")
    public Result<MediaView> completeUpload(HttpServletRequest request,
                                            @Valid @RequestBody CompleteUploadRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.USER_ID_ATTRIBUTE);
        return Result.ok(chunkUploadService.completeUpload(userId, body));
    }
}
