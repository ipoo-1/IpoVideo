package com.ipovideo.service;

import com.ipovideo.dto.VideoEvidenceHit;
import com.ipovideo.dto.VideoSegment;
import com.ipovideo.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@ActiveProfiles("test")
class VectorStoreServiceTest {

    @Autowired
    private VectorStoreService vectorStoreService;

    @Autowired
    private EmbeddingUtils embeddingUtils;

    @Test
    void keywordSearchFindsMatchingSegment() {
        List<VideoSegment> segments = List.of(
                new VideoSegment(0, 1000, "介绍二叉树的遍历方式", "二叉树", List.of()),
                new VideoSegment(1000, 2000, "中序遍历的顺序是左根右", "中序", List.of())
        );
        List<VideoEvidenceHit> hits = vectorStoreService.keywordSearch("遍历", segments, 5);
        assertFalse(hits.isEmpty(), "关键词检索应命中包含'遍历'的片段");
    }

    @Test
    void upsertAndSemanticSearch() throws Exception {
        List<Float> vector = embeddingUtils.embed("二叉树的前序遍历");
        if (vector.isEmpty()) {
            return; // 未配置 API Key 时跳过真实向量检索
        }
        List<VideoSegment> segments = List.of(
                new VideoSegment(0, 1000, "二叉树的前序遍历是根左右", "前序遍历", List.of()),
                new VideoSegment(1000, 2000, "中序遍历的顺序是左根右", "中序", List.of())
        );
        vectorStoreService.upsertSegments(90001L, segments);

        List<VideoEvidenceHit> hits = vectorStoreService.search("前序遍历", segments, 5);
        assertFalse(hits.isEmpty(), "向量检索应返回命中片段");
        assertEquals(0, hits.get(0).startMs(), "最相关片段应是第一段");
    }
}
