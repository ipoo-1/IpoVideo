# 集成测试代码速查卡

## 一句话模型

测试 = 安排场景 -> 发请求 -> 检查结果

```text
先造数据（注册、登录、上传）
    ▼
发 HTTP 请求（mockMvc.perform）
    ▼
检查响应（andExpect / assertEquals）
```

## 常用 API 翻译表

| 代码 | 大白话 |
| :--- | :--- |
| `@Test` | 告诉 JUnit：这是一个测试方法 |
| `mockMvc.perform(post("/api/tasks"))` | 向 `/api/tasks` 发一个 POST 请求 |
| `.contentType(MediaType.APPLICATION_JSON)` | 请求体格式是 JSON |
| `.content("...json...")` | 请求体内容 |
| `.header("Authorization", "Bearer " + token)` | 请求头里带上登录凭证 |
| `.andExpect(status().isOk())` | 断言：响应状态必须是 200 |
| `.andExpect(jsonPath("$.code").value(0))` | 断言：JSON 里的 code 必须是 0 |
| `MockMultipartFile(...)` | 造一个假的上传文件 |
| `readJson(result)` | 把响应 JSON 读成一棵树 |
| `.path("data").path("id").asLong()` | 从树里取 `data.id` 字段 |
| `assertEquals(期望, 实际)` | 断言：两者必须相等 |

## 读懂一个测试的模板

```java
@Test
void 测试名字() throws Exception {
    // 第 1 步：准备场景（注册、登录、上传）
    ...

    // 第 2 步：发请求（要测哪个接口就调哪个）
    mockMvc.perform(get("/api/tasks")
            .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());   // 第 3 步：检查响应
}
```

测试名字通常就是一句话：`listReturnsOwnTasksNewestFirst` =
“列表接口返回自己的任务，且最新的排最前”。

## JSON 树怎么读

接口返回：

```json
{"code":0,"message":"success","data":[{"id":5,"status":"PENDING"}]}
```

用 `readJson(result)` 后：

```java
JsonNode data = readJson(result).path("data"); // 取 data，是个数组
data.get(0).path("id").asLong();               // 取第一个元素的 id -> 5
data.isArray();                                // 检查它是不是数组 -> true
```

## 给新手的建议

1. 不需要背语法，把这份速查卡放在手边，写测试时对照着抄。
2. 先复制现有测试改一改，再自己写新的。
3. 面试时重点讲“这个测试验证了什么”，不要纠结 `andExpect` 怎么写。
4. 报错先看“期望 vs 实际”，比如 `expected: 200 but was: 500`，先想为什么返回 500，再查代码。
