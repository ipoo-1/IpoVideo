package com.ipovideo.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class FfmpegUtils {

    private final String ffmpegPath;

    public FfmpegUtils(@Value("${tool.ffmpeg.path:D:\\FFmpeg\\ffmpeg-master-latest-win64-gpl\\bin\\ffmpeg.exe}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public void extractAudio(String videoPath, String audioPath) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-y", "-i", videoPath,
                "-vn", "-acodec", "libmp3lame", audioPath);
        runProcess(builder, "抽音频");
    }

    public void extractFrames(String videoPath, String outputDir, String outputPattern) throws Exception {
        String filter = "select='gt(scene,0.35)+eq(n,0)',setpts=N/FRAME_RATE/TB";
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-y", "-i", videoPath,
                "-vf", filter,
                "-fps_mode", "vfr",
                outputDir + "\\" + outputPattern);
        runProcess(builder, "抽关键帧");
    }

    private void runProcess(ProcessBuilder builder, String action) throws Exception {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg " + action + "超时");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffmpeg " + action + "失败，退出码=" + process.exitValue()
                    + "\n" + output);
        }
    }
}