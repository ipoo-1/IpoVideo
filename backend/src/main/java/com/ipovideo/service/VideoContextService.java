package com.ipovideo.service;

import com.ipovideo.dto.VideoContext;
import com.ipovideo.dto.VideoSegment;
import com.ipovideo.utils.AudioTranscriptionUtils;
import com.ipovideo.utils.FfmpegUtils;
import com.ipovideo.utils.OcrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 视频上下文构建：并行两条分支（音频 ASR + 画面 OCR），
 * 单条分支失败不拖垮整体，两条都失败才算失败。
 */
@Service
public class VideoContextService {

    private static final Logger log = LoggerFactory.getLogger(VideoContextService.class);

    private final FfmpegUtils ffmpegUtils;
    private final OcrUtils ocrUtils;
    private final AudioTranscriptionUtils audioTranscriptionUtils;

    public VideoContextService(FfmpegUtils ffmpegUtils,
                               OcrUtils ocrUtils,
                               AudioTranscriptionUtils audioTranscriptionUtils) {
        this.ffmpegUtils = ffmpegUtils;
        this.ocrUtils = ocrUtils;
        this.audioTranscriptionUtils = audioTranscriptionUtils;
    }

    public VideoContext buildContext(Path videoPath, Path workDir) throws Exception {
        // 音频分支：抽音频 -> ASR
        String asrText = "";
        try {
            Path audioFile = workDir.resolve("audio.mp3");
            ffmpegUtils.extractAudio(videoPath.toString(), audioFile.toString());
            asrText = audioTranscriptionUtils.transcribe(audioFile);
        } catch (Exception ex) {
            log.warn("video_context_audio_branch_failed", ex);
        }

        // 画面分支：抽关键帧 -> 逐帧 OCR，单帧失败继续下一帧
        List<String> frames = new ArrayList<>();
        List<String> ocrParts = new ArrayList<>();
        try {
            Path frameDir = workDir.resolve("frames");
            Files.createDirectories(frameDir);
            ffmpegUtils.extractFrames(videoPath.toString(), frameDir.toString(), "frame-%03d.jpg");
            try (Stream<Path> files = Files.list(frameDir)) {
                List<Path> jpgs = files
                        .filter(p -> p.getFileName().toString().endsWith(".jpg"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                for (Path frame : jpgs) {
                    frames.add(frame.getFileName().toString());
                    try {
                        String text = ocrUtils.recognizeText(frame.toString());
                        if (text != null && !text.isBlank()) {
                            ocrParts.add(text);
                        }
                    } catch (Exception ex) {
                        log.warn("video_context_ocr_frame_failed frame={}", frame.getFileName(), ex);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("video_context_visual_branch_failed", ex);
        }

        // 两条分支都失败才算失败
        if (asrText.isBlank() && ocrParts.isEmpty()) {
            throw new IllegalStateException("视频上下文构建失败：ASR 与 OCR 均无有效内容");
        }

        VideoSegment segment = new VideoSegment(
                0,
                -1,
                asrText,
                String.join("\n", ocrParts),
                frames);
        return new VideoContext(List.of(segment));
    }
}
