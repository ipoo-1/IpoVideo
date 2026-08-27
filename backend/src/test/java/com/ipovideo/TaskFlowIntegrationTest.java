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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3 端到端测试：上传 -> 创建异步任务 -> 轮询等待后台线程完成。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void uploadCreateTaskAndWaitForSuccess() throws Exception {
        String username = "task_user_" + (System.currentTimeMillis() % 1_000_000);
        String auth = """
                {"username":"%s","password":"secret123"}
                """.formatted(username);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(auth))
                .andExpect(status().isOk());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(auth))
                .andExpect(status().isOk())
                .andReturn();
        String token = readToken(login);

        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.mp4", "video/mp4", new byte[]{1, 2, 3, 4});
        MvcResult upload = mockMvc.perform(multipart("/api/media/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andReturn();
        long mediaId = readJson(upload).path("data").path("id").asLong();

        MvcResult create = mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mediaId":%d,"goal":"整理核心知识点"}
                                """.formatted(mediaId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        long taskId = readJson(create).path("data").path("id").asLong();

        String finalStatus = "PENDING";
        for (int i = 0; i < 50; i++) {
            Thread.sleep(200);
            MvcResult result = mockMvc.perform(get("/api/tasks/" + taskId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode body = readJson(result);
            finalStatus = body.path("data").path("status").asText();
            if ("SUCCESS".equals(finalStatus) || "FAILED".equals(finalStatus)) {
                break;
            }
        }
        assertEquals("SUCCESS", finalStatus);
    }

    @Test
    void taskBelongsToCreatorOnly() throws Exception {
        String usernameA = "owner_a_" + (System.currentTimeMillis() % 1_000_000);
        String usernameB = "owner_b_" + (System.currentTimeMillis() % 1_000_000);
        String authA = """
                {"username":"%s","password":"secret123"}
                """.formatted(usernameA);
        String authB = """
                {"username":"%s","password":"secret123"}
                """.formatted(usernameB);

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(authA))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(authB))
                .andExpect(status().isOk());

        String tokenA = readToken(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(authA)).andExpect(status().isOk()).andReturn());
        String tokenB = readToken(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(authB)).andExpect(status().isOk()).andReturn());

        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.mp4", "video/mp4", new byte[]{1});
        MvcResult upload = mockMvc.perform(multipart("/api/media/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        long mediaId = readJson(upload).path("data").path("id").asLong();

        MvcResult create = mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mediaId":%d,"goal":"整理知识点"}
                                """.formatted(mediaId)))
                .andExpect(status().isOk())
                .andReturn();
        long taskId = readJson(create).path("data").path("id").asLong();

        mockMvc.perform(get("/api/tasks/" + taskId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void createTaskRequiresGoal() throws Exception {
        String username = "goal_user_" + (System.currentTimeMillis() % 1_000_000);
        String auth = """
                {"username":"%s","password":"secret123"}
                """.formatted(username);
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(auth))
                .andExpect(status().isOk());
        String token = readToken(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(auth)).andExpect(status().isOk()).andReturn());

        mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mediaId":1,"goal":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
    @Test
    void listReturnsOwnTasksNewestFirst() throws Exception {
        String username = "list_user_" + (System.currentTimeMillis() % 1_000_000);
        String auth = """
            {"username":"%s","password":"secret123"}
            """.formatted(username);

        // 1. 注册 + 登录，拿 token
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(auth))
                .andExpect(status().isOk());
        String token = readToken(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(auth))
                .andExpect(status().isOk())
                .andReturn());

        // 2. 上传一个视频
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.mp4", "video/mp4", new byte[]{1, 2, 3, 4});
        long mediaId = readJson(mockMvc.perform(multipart("/api/media/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asLong();

        // 3. 创建任务
        long taskId = readJson(mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"mediaId":%d,"goal":"整理知识点"}
                    """.formatted(mediaId)))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asLong();

        // 4. 调任务列表接口，断言：返回的是数组，且第一个就是刚创建的任务
        MvcResult listResult = mockMvc.perform(get("/api/tasks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = readJson(listResult).path("data");
        assertEquals(true, data.isArray());
        assertEquals(taskId, data.get(0).path("id").asLong());
    }

    @Test
    void duplicateTaskSubmissionRejected() throws Exception {
        String username = "dup_user_" + (System.currentTimeMillis() % 1_000_000);
        String auth = """
                {"username":"%s","password":"secret123"}
                """.formatted(username);
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(auth))
                .andExpect(status().isOk());
        String token = readToken(mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(auth)).andExpect(status().isOk()).andReturn());

        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.mp4", "video/mp4", new byte[]{9});
        long mediaId = readJson(mockMvc.perform(multipart("/api/media/upload")
                .file(file)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asLong();

        String body = """
                {"mediaId":%d,"goal":"整理知识点"}
                """.formatted(mediaId);
        mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 同一视频在任务完成前重复提交，应被拦下
        mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    private String readToken(MvcResult loginResult) throws Exception {
        return readJson(loginResult).path("data").path("token").asText();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
