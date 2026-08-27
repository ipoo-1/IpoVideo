package com.ipovideo.utils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@SpringBootTest
@ActiveProfiles("test")
class FfmpegUtilsTest {

    @Autowired
    private FfmpegUtils ffmpegUtils;

    @Test
    void extractAudioFromSampleVideo() throws Exception {
        Path video = Path.of("target/stage8-test/test.mp4");
        if (!Files.exists(video)) {
            return; // 没有样例视频就跳过
        }
        Path audio = Path.of("target/stage8-test/test.mp3");
        ffmpegUtils.extractAudio(video.toString(), audio.toString());
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(audio), "音频文件应生成");
    }

    @Test
    void extractFramesFromSampleVideo() throws Exception {
        Path video = Path.of("target/stage8-test/test.mp4");
        if (!Files.exists(video)) {
            return; // 没有样例视频就跳过
        }
        Path dir = Path.of("target/stage8-test/frames");
        Files.createDirectories(dir);
        ffmpegUtils.extractFrames(video.toString(), dir.toString(), "frame-%03d.jpg");

        try (Stream<Path> files = Files.list(dir)) {
            long count = files.filter(p -> p.getFileName().toString().startsWith("frame-")).count();
            org.junit.jupiter.api.Assertions.assertTrue(count > 0, "应生成至少一帧");
        }
    }
}
