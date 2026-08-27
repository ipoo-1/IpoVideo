package com.ipovideo.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class OcrUtils {

    private final String tesseractPath;

    public OcrUtils(@Value("${tool.ocr.path:D:\\tesseract\\tesseract.exe}") String tesseractPath) {
        this.tesseractPath = tesseractPath;
    }

    public String recognizeText(String imagePath) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                tesseractPath, imagePath, "stdout", "-l", "chi_sim+eng");
        return runAndRead(builder);
    }

    private String runAndRead(ProcessBuilder builder) throws Exception {
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
            throw new IllegalStateException("tesseract OCR 超时");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("tesseract OCR 失败，退出码=" + process.exitValue()
                    + "\n" + output);
        }
        return output.toString().trim();
    }
}