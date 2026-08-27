package com.ipovideo.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * ASR 客户端：手动拼 multipart/form-data 报文，用 JDK HttpURLConnection 调用
 * SiliconFlow 语音识别接口，与 curl -F 的报文格式一致。
 */
@Component
public class AudioTranscriptionUtils {

    private final String apiKey;
    private final String asrUrl;
    private final String asrModel;
    private final ObjectMapper objectMapper;

    public AudioTranscriptionUtils(
            @Value("${ai.deepseek.api-key:}") String apiKey,
            @Value("${ai.asr.url:https://api.siliconflow.cn/v1/audio/transcriptions}") String asrUrl,
            @Value("${ai.asr.model:TeleAI/TeleSpeechASR}") String asrModel,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.asrUrl = asrUrl;
        this.asrModel = asrModel;
        this.objectMapper = objectMapper;
    }

    public String transcribe(Path audioFile) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }

        String boundary = "----DOVideo" + UUID.randomUUID();
        byte[] fileBytes = Files.readAllBytes(audioFile);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeTextPart(body, boundary, "model", asrModel);
        writeFilePart(body, boundary, "file", audioFile.getFileName().toString(), fileBytes);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection conn = (HttpURLConnection) new URL(asrUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.toByteArray());
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            String error = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            throw new IllegalStateException("ASR 请求失败: HTTP " + code + " " + error);
        }
        String json = readAll(conn.getInputStream());
        JsonNode node = objectMapper.readTree(json);
        return node.path("text").asText("");
    }

    private void writeTextPart(ByteArrayOutputStream out, String boundary, String name, String value)
            throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeFilePart(ByteArrayOutputStream out, String boundary, String name,
                               String filename, byte[] data) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\""
                + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
