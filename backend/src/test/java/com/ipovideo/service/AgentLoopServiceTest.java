package com.ipovideo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipovideo.common.BusinessException;
import com.ipovideo.dto.AgentPlan;
import com.ipovideo.dto.AgentResult;
import com.ipovideo.dto.CriticResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLoopServiceTest {

    @Test
    void retriesWhenConclusionHasNoEvidenceCitation() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.structuredChat(anyString(), anyString(), eq(AgentPlan.class)))
                .thenReturn(new AgentPlan("目标", List.of("提取结论")));
        when(client.structuredChat(anyString(), anyString(), eq(AgentResult.class)))
                .thenReturn(new AgentResult("第一版", List.of("没有引用"), List.of()),
                        new AgentResult("第二版", List.of("有引用 [E1]"), List.of()));
        when(client.structuredChat(anyString(), anyString(), eq(CriticResult.class)))
                .thenReturn(new CriticResult(true, List.of()));

        AgentLoopService service = new AgentLoopService(client, new ObjectMapper());
        AgentResult result = service.run("总结视频", "[E1] [0ms - 1000ms] 证据");

        assertEquals("第二版", result.title());
    }

    @Test
    void rejectsResultThatKeepsReferencingMissingEvidence() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.structuredChat(anyString(), anyString(), eq(AgentPlan.class)))
                .thenReturn(new AgentPlan("目标", List.of("提取结论")));
        when(client.structuredChat(anyString(), anyString(), eq(AgentResult.class)))
                .thenReturn(new AgentResult("错误结果", List.of("引用了不存在证据 [E9]"), List.of()));
        when(client.structuredChat(anyString(), anyString(), eq(CriticResult.class)))
                .thenReturn(new CriticResult(true, List.of()));

        AgentLoopService service = new AgentLoopService(client, new ObjectMapper());

        assertThrows(BusinessException.class,
                () -> service.run("总结视频", "[E1] [0ms - 1000ms] 证据"));
    }
}
