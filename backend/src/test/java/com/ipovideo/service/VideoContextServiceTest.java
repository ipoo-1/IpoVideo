package com.ipovideo.service;

import com.ipovideo.dto.VideoContext;
import com.ipovideo.utils.AudioTranscriptionUtils;
import com.ipovideo.utils.FfmpegUtils;
import com.ipovideo.utils.OcrUtils;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoContextServiceTest {

    @Test
    void splitsLongVideoIntoTimeWindows() throws Exception {
        Path tempDir = Path.of("target/video-context-window-test-" + System.nanoTime());
        Files.createDirectories(tempDir);

        FfmpegUtils ffmpeg = mock(FfmpegUtils.class);
        OcrUtils ocr = mock(OcrUtils.class);
        AudioTranscriptionUtils audio = mock(AudioTranscriptionUtils.class);

        when(ffmpeg.probeDurationMillis("video.mp4")).thenReturn(120_000L);
        doNothing().when(ffmpeg).extractAudio(anyString(), anyString());
        when(audio.transcribe(any())).thenReturn("第一句。第二句。第三句。第四句。");
        doNothing().when(ffmpeg).extractSampledFrames(anyString(), anyString(), anyString(), eq(30));

        Path frames = tempDir.resolve("frames");
        Files.createDirectories(frames);
        for (int index = 1; index <= 4; index++) {
            Files.createFile(frames.resolve("frame-%04d.jpg".formatted(index)));
        }
        when(ocr.recognizeText(anyString())).thenReturn("OCR画面文字");

        VideoContextService service = new VideoContextService(ffmpeg, ocr, audio, 30);
        VideoContext context = service.buildContext(Path.of("video.mp4"), tempDir);

        assertFalse(context.segments().isEmpty());
        assertEquals(4, context.segments().size());
        assertEquals(0, context.segments().get(0).startMs());
        assertEquals(30_000, context.segments().get(0).endMs());
        assertEquals(120_000, context.segments().get(3).endMs());
        assertEquals("ASR_ESTIMATED+OCR_FRAME",
                context.segments().get(0).timestampSource());
    }
}
