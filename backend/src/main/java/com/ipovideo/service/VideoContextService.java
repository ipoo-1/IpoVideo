package com.ipovideo.service;

import com.ipovideo.dto.VideoContext;
import com.ipovideo.dto.VideoSegment;
import com.ipovideo.utils.AudioTranscriptionUtils;
import com.ipovideo.utils.FfmpegUtils;
import com.ipovideo.utils.OcrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 视频上下文构建：按时间窗口切分 ASR 和 OCR，形成可检索、可定位的 Evidence。
 */
@Service
public class VideoContextService {

    private static final Logger log = LoggerFactory.getLogger(VideoContextService.class);

    private final FfmpegUtils ffmpegUtils;
    private final OcrUtils ocrUtils;
    private final AudioTranscriptionUtils audioTranscriptionUtils;
    private final int windowSeconds;

    public VideoContextService(FfmpegUtils ffmpegUtils,
                               OcrUtils ocrUtils,
                               AudioTranscriptionUtils audioTranscriptionUtils,
                               @Value("${app.video.segment-window-seconds:30}") int windowSeconds) {
        this.ffmpegUtils = ffmpegUtils;
        this.ocrUtils = ocrUtils;
        this.audioTranscriptionUtils = audioTranscriptionUtils;
        this.windowSeconds = Math.max(5, windowSeconds);
    }

    public VideoContext buildContext(Path videoPath, Path workDir) throws Exception {
        long durationMs = probeDurationSafely(videoPath);

        String asrText = "";
        try {
            Path audioFile = workDir.resolve("audio.mp3");
            ffmpegUtils.extractAudio(videoPath.toString(), audioFile.toString());
            asrText = audioTranscriptionUtils.transcribe(audioFile);
        } catch (Exception ex) {
            log.warn("video_context_audio_branch_failed", ex);
        }

        List<Path> frames = extractFramesSafely(videoPath, workDir);
        int windowCount = calculateWindowCount(durationMs, frames.size());
        List<String> asrWindows = distributeText(asrText, windowCount);
        List<VideoSegment> segments = new ArrayList<>();

        long windowMs = windowSeconds * 1000L;
        for (int index = 0; index < windowCount; index++) {
            long startMs = durationMs > 0 ? index * windowMs : 0;
            long endMs;
            if (durationMs > 0) {
                endMs = Math.min(durationMs, startMs + windowMs);
            } else {
                endMs = -1;
            }

            Path frame = index < frames.size() ? frames.get(index) : null;
            String ocrText = recognizeFrame(frame);
            String asrWindow = asrWindows.get(index);
            if (asrWindow.isBlank() && ocrText.isBlank()) {
                continue;
            }
            String source;
            if (!asrWindow.isBlank() && !ocrText.isBlank()) {
                source = "ASR_ESTIMATED+OCR_FRAME";
            } else if (!asrWindow.isBlank()) {
                source = "ASR_ESTIMATED";
            } else {
                source = "OCR_FRAME";
            }
            List<String> frameNames = frame == null
                    ? List.of()
                    : List.of(frame.getFileName().toString());
            segments.add(new VideoSegment(startMs, endMs, asrWindow, ocrText, frameNames, source));
        }

        if (segments.isEmpty()) {
            throw new IllegalStateException("视频上下文构建失败：ASR 与 OCR 均无有效内容");
        }
        return new VideoContext(segments);
    }

    private long probeDurationSafely(Path videoPath) {
        try {
            return ffmpegUtils.probeDurationMillis(videoPath.toString());
        } catch (Exception ex) {
            log.warn("video_context_duration_probe_failed", ex);
            return -1;
        }
    }

    private List<Path> extractFramesSafely(Path videoPath, Path workDir) {
        List<Path> frames = new ArrayList<>();
        try {
            Path frameDir = workDir.resolve("frames");
            Files.createDirectories(frameDir);
            ffmpegUtils.extractSampledFrames(
                    videoPath.toString(), frameDir.toString(), "frame-%04d.jpg", windowSeconds);
            try (Stream<Path> files = Files.list(frameDir)) {
                frames.addAll(files
                        .filter(path -> path.getFileName().toString().endsWith(".jpg"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList());
            }
        } catch (Exception ex) {
            log.warn("video_context_visual_branch_failed", ex);
        }
        return frames;
    }

    private String recognizeFrame(Path frame) {
        if (frame == null) {
            return "";
        }
        try {
            String text = ocrUtils.recognizeText(frame.toString());
            return text == null ? "" : text.trim();
        } catch (Exception ex) {
            log.warn("video_context_ocr_frame_failed frame={}", frame.getFileName(), ex);
            return "";
        }
    }

    private int calculateWindowCount(long durationMs, int frameCount) {
        int durationWindows = durationMs > 0
                ? (int) Math.ceil((double) durationMs / (windowSeconds * 1000L))
                : 0;
        return Math.max(1, Math.max(durationWindows, frameCount));
    }

    private List<String> distributeText(String text, int windows) {
        if (text == null || text.isBlank()) {
            return emptyWindows(windows);
        }
        String normalized = text.trim();
        String[] sentences = normalized.split("(?<=[。！？.!?])");
        if (sentences.length < windows) {
            return splitByLength(normalized, windows);
        }

        List<String> result = new ArrayList<>();
        int targetLength = (int) Math.ceil((double) normalized.length() / windows);
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() > 0 && current.length() + sentence.length() > targetLength
                    && result.size() < windows - 1) {
                result.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        while (result.size() < windows) {
            result.add("");
        }
        return result.subList(0, windows);
    }

    private List<String> splitByLength(String text, int windows) {
        int chunkSize = Math.max(1, (int) Math.ceil((double) text.length() / windows));
        List<String> result = new ArrayList<>();
        for (int index = 0; index < windows; index++) {
            int start = Math.min(text.length(), index * chunkSize);
            int end = Math.min(text.length(), start + chunkSize);
            result.add(text.substring(start, end).trim());
        }
        return result;
    }

    private List<String> emptyWindows(int windows) {
        List<String> result = new ArrayList<>(windows);
        for (int index = 0; index < windows; index++) {
            result.add("");
        }
        return result;
    }
}
