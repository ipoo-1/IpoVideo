package com.ipovideo.service;

import com.ipovideo.dto.VideoEvidenceHit;
import com.ipovideo.dto.VideoSegment;
import com.ipovideo.utils.EmbeddingUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量检索服务：片段写入 Qdrant，用户目标语义召回 TopK；
 * Embedding 或 Qdrant 不可用时降级为本地关键词匹配。
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);
    private static final int VECTOR_SIZE = 1024;

    private final EmbeddingUtils embeddingUtils;
    private final ObjectMapper objectMapper;
    private final String qdrantUrl;
    private final String collection;

    public VectorStoreService(EmbeddingUtils embeddingUtils,
                              ObjectMapper objectMapper,
                              @Value("${vector.qdrant.url:http://localhost:6333}") String qdrantUrl,
                              @Value("${vector.qdrant.collection:video_segments}") String collection) {
        this.embeddingUtils = embeddingUtils;
        this.objectMapper = objectMapper;
        this.qdrantUrl = qdrantUrl;
        this.collection = collection;
    }

    public void upsertSegments(Long mediaId, List<VideoSegment> segments) {
        try {
            ensureCollection();
            List<Map<String, Object>> points = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                VideoSegment segment = segments.get(i);
                String text = joinText(segment);
                List<Float> vector = embeddingUtils.embed(text);
                if (vector.isEmpty()) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("mediaId", mediaId);
                payload.put("startMs", segment.startMs());
                payload.put("endMs", segment.endMs());
                payload.put("asrText", segment.asrText());
                payload.put("ocrText", segment.ocrText());
                payload.put("timestampSource", segment.timestampSource());

                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", mediaId * 100_000L + i);
                point.put("vector", vector);
                point.put("payload", payload);
                points.add(point);
            }
            if (!points.isEmpty()) {
                sendJson("PUT", "/collections/" + collection + "/points?wait=true",
                        Map.of("points", points));
            }
        } catch (Exception ex) {
            // 写入失败不影响主流程：检索时会走降级
            log.warn("vector_upsert_failed mediaId={} error={}", mediaId, ex.getMessage());
        }
    }

    public List<VideoEvidenceHit> search(String goalText, List<VideoSegment> segments, int limit) {
        List<Float> goalVector;
        try {
            goalVector = embeddingUtils.embed(goalText);
        } catch (Exception ex) {
            log.warn("embedding_failed fallback_to_keyword error={}", ex.getMessage());
            return keywordSearch(goalText, segments, limit);
        }
        if (goalVector.isEmpty()) {
            return keywordSearch(goalText, segments, limit);
        }

        try {
            String body = sendJson("POST", "/collections/" + collection + "/points/search",
                    Map.of("vector", goalVector, "limit", limit, "with_payload", true));
            JsonNode result = objectMapper.readTree(body).path("result");
            List<VideoEvidenceHit> hits = new ArrayList<>();
            for (JsonNode point : result) {
                JsonNode payload = point.path("payload");
                hits.add(new VideoEvidenceHit(
                        payload.path("startMs").asLong(),
                        payload.path("endMs").asLong(),
                        joinText(payload),
                        point.path("score").asDouble(),
                        payload.path("timestampSource").asText("UNKNOWN")
                ));
            }
            return hits;
        } catch (Exception ex) {
            log.warn("qdrant_search_failed fallback_to_keyword error={}", ex.getMessage());
            return keywordSearch(goalText, segments, limit);
        }
    }

    /**
     * 降级检索：把目标拆成关键词，在片段文字里数命中次数，按命中次数取 TopK。
     */
    public List<VideoEvidenceHit> keywordSearch(String goalText, List<VideoSegment> segments, int limit) {
        List<String> keywords = tokenize(goalText);
        return segments.stream()
                .map(segment -> {
                    String text = joinText(segment);
                    int score = 0;
                    for (String keyword : keywords) {
                        if (keyword.length() >= 2 && text.contains(keyword)) {
                            score++;
                        }
                    }
                    return new VideoEvidenceHit(
                            segment.startMs(), segment.endMs(), text, score, segment.timestampSource());
                })
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingDouble(VideoEvidenceHit::score).reversed())
                .limit(limit)
                .toList();
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : text.split("[^\\p{IsHan}\\p{Alnum}]+")) {
            if (!token.isBlank()) {
                tokens.add(token.toLowerCase());
            }
        }
        return tokens;
    }

    private String joinText(VideoSegment segment) {
        return joinParts(segment.asrText(), segment.ocrText());
    }

    private String joinText(JsonNode payload) {
        return joinParts(payload.path("asrText").asText(""), payload.path("ocrText").asText(""));
    }

    private String joinParts(String asrText, String ocrText) {
        StringBuilder sb = new StringBuilder();
        if (asrText != null && !asrText.isBlank()) {
            sb.append(asrText);
        }
        if (ocrText != null && !ocrText.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(ocrText);
        }
        return sb.toString();
    }

    private void ensureCollection() throws Exception {
        sendJson("PUT", "/collections/" + collection,
                Map.of("vectors", Map.of("size", VECTOR_SIZE, "distance", "Cosine")));
    }

    private String sendJson(String method, String path, Object body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(qdrantUrl + path).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(objectMapper.writeValueAsBytes(body));
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        if (code >= 400 && !(path.equals("/collections/" + collection) && code == 409)) {
            throw new IllegalStateException("Qdrant 请求失败: HTTP " + code + " " + response);
        }
        return response;
    }
}
