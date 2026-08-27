package com.ipovideo.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EmbeddingUtils {

    private final String apiKey;
    private final String embeddingUrl;
    private final String model;
    private final ObjectMapper objectMapper;

    public EmbeddingUtils(
            @Value("${ai.deepseek.api-key:}") String apiKey,
            @Value("${ai.embedding.url:https://api.siliconflow.cn/v1/embeddings}") String embeddingUrl,
            @Value("${ai.embedding.model:BAAI/bge-m3}") String model,
            ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.embeddingUrl = embeddingUrl;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    public List<Float> embed(String text) throws Exception {
        if (apiKey == null || apiKey.isBlank() || text == null || text.isBlank()) {
            return List.of();
        }

        String requestBody = objectMapper.writeValueAsString(
                Map.of("model", model, "input", text));

        HttpURLConnection conn = (HttpURLConnection) new URL(embeddingUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            InputStream errorStream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String error = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("Embedding 请求失败: HTTP " + code + " " + error);
        }

        String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(json);
        JsonNode embedding = node.path("data").get(0).path("embedding");
        List<Float> result = new ArrayList<>();
        for (JsonNode value : embedding) {
            result.add(value.floatValue());
        }
        return result;
    }
}