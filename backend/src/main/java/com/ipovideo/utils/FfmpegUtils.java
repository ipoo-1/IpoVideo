package com.ipovideo.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FfmpegUtils {

    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Duration: (\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?");

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
                Path.of(outputDir).resolve(outputPattern).toString());
        runProcess(builder, "抽关键帧");
    }

    public void extractSampledFrames(String videoPath,
                                     String outputDir,
                                     String outputPattern,
                                     int intervalSeconds) throws Exception {
        int interval = Math.max(1, intervalSeconds);
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-y", "-i", videoPath,
                "-vf", "fps=1/" + interval,
                Path.of(outputDir).resolve(outputPattern).toString());
        runProcess(builder, "按时间窗口抽帧");
    }

    public long probeDurationMillis(String videoPath) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-hide_banner", "-i", videoPath);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = readProcessOutput(process);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("ffmpeg 读取视频信息超时");
        }
        Matcher matcher = DURATION_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("无法从 ffmpeg 输出解析视频时长");
        }
        long hours = Long.parseLong(matcher.group(1));
        long minutes = Long.parseLong(matcher.group(2));
        long seconds = Long.parseLong(matcher.group(3));
        String fraction = matcher.group(4);
        long millis = 0;
        if (fraction != null) {
            String normalized = (fraction + "000").substring(0, 3);
            millis = Long.parseLong(normalized);
        }
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis;
    }

    private void runProcess(ProcessBuilder builder, String action) throws Exception {
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = readProcessOutput(process);
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

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }
}
