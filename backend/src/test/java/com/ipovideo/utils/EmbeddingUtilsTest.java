package com.ipovideo.utils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class EmbeddingUtilsTest {

    @Autowired
    private EmbeddingUtils embeddingUtils;

    @Test
    void embedTextProducesVector() throws Exception {
        List<Float> vector = embeddingUtils.embed("二叉树的前序遍历");
        if (vector.isEmpty()) {
            return; // 未配置 API Key 时跳过真实调用
        }
        assertEquals(1024, vector.size(), "BGE-M3 向量维度应为 1024");
        assertTrue(vector.stream().anyMatch(v -> v != 0f), "向量不应全为 0");
    }
}
