package com.ipovideo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 6 分片上传测试：init -> 3 片 -> complete。
 * 注意：S3/MinIO 合并时除最后一片外，每片必须 >= 5MiB。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MediaFlowIntegrationTest {

    private static final int PART_BYTES = 6 * 1024 * 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void chunkUploadFlow() throws Exception {
        String username = "chunk_user_" + (System.currentTimeMillis() % 1_000_000);
        String auth = """
                {"username":"%s","password":"secret123"}
                """.formatted(username);
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(auth))
                .andExpect(status().isOk());
        String token = readToken(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(auth)).andExpect(status().isOk()).andReturn());

        MvcResult init = mockMvc.perform(post("/api/media/upload/init")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"demo.mp4","totalParts":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String uploadId = readJson(init).path("data").path("uploadId").asText();

        uploadPart(token, uploadId, 1, new byte[PART_BYTES]);
        uploadPart(token, uploadId, 2, new byte[PART_BYTES]);
        uploadPart(token, uploadId, 3, new byte[]{8});

        MvcResult complete = mockMvc.perform(post("/api/media/upload/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s"}
                                """.formatted(uploadId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.filePath").isNotEmpty())
                .andReturn();
        String filePath = readJson(complete).path("data").path("filePath").asText();
        assertTrue(filePath.startsWith("media/"), "filePath 应该是 MinIO 对象键: " + filePath);

        // 完成后元信息已删除，重复 complete 应 404
        mockMvc.perform(post("/api/media/upload/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s"}
                                """.formatted(uploadId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void completeWithoutAllPartsReturns400() throws Exception {
        String username = "chunk_bad_" + (System.currentTimeMillis() % 1_000_000);
        String auth = """
                {"username":"%s","password":"secret123"}
                """.formatted(username);
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(auth))
                .andExpect(status().isOk());
        String token = readToken(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(auth)).andExpect(status().isOk()).andReturn());

        MvcResult init = mockMvc.perform(post("/api/media/upload/init")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"demo.mp4","totalParts":2}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = readJson(init).path("data").path("uploadId").asText();

        uploadPart(token, uploadId, 1, new byte[]{1, 2, 3});

        mockMvc.perform(post("/api/media/upload/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s"}
                                """.formatted(uploadId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private void uploadPart(String token, String uploadId, int partNumber, byte[] bytes) throws Exception {
        MockMultipartFile part = new MockMultipartFile(
                "file", "part" + partNumber, "application/octet-stream", bytes);
        mockMvc.perform(multipart("/api/media/upload/part")
                        .file(part)
                        .param("uploadId", uploadId)
                        .param("partNumber", String.valueOf(partNumber))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String readToken(MvcResult loginResult) throws Exception {
        return readJson(loginResult).path("data").path("token").asText();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
