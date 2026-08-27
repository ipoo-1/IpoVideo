package com.ipovideo.service;

import com.ipovideo.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型调用客户端：负责调 ChatModel、清理 Markdown 代码块、解析 JSON。
 * 解析失败时带"必须返回严格 JSON"的提示重试一次，仍失败就抛业务异常。
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DeepSeekClient(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public <T> T structuredChat(String systemPrompt, String userPrompt, Class<T> type) {
        try {
            String text = cleanJson(chatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)).aiMessage().text());
            return objectMapper.readValue(text, type);
        } catch (Exception first) {
            log.warn("model_json_parse_failed retry_once error={}", first.getMessage());
            String retry = chatModel.chat(
                    SystemMessage.from(systemPrompt + "\n你上次没有返回合法 JSON，请只返回严格 JSON，不要使用 Markdown 代码块。"),
                    UserMessage.from(userPrompt)).aiMessage().text();
            try {
                return objectMapper.readValue(cleanJson(retry), type);
            } catch (Exception second) {
                log.error("model_json_parse_failed_after_retry", second);
                throw new BusinessException(500, "模型输出解析失败");
            }
        }
    }

    /**
     * 模型偶尔会把 JSON 包在 ```json ... ``` 代码块里，这里剥掉。
     */
    private String cleanJson(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }
}
