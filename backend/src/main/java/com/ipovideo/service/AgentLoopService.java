package com.ipovideo.service;

import com.ipovideo.common.BusinessException;
import com.ipovideo.dto.AgentPlan;
import com.ipovideo.dto.AgentResult;
import com.ipovideo.dto.CriticResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受控 Agent 工作流：Planner -> Executor -> Critic，最多两轮。
 * 程序负责轮次和状态，模型负责理解和生成。
 */
@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_ROUNDS = 2;

    private static final String PLANNER_SYSTEM = """
            你是 IpoVideo 的 Planner。理解用户目标并拆成 1-5 个可执行任务。
            只返回严格 JSON，不要 Markdown 代码块，格式：
            {"understoodGoal":"...","tasks":["...","..."]}
            """;

    private static final String EXECUTOR_SYSTEM = """
            你是 IpoVideo 的 Executor。根据计划生成结构化结论。
            只返回严格 JSON，不要 Markdown 代码块，格式：
            {"title":"...","conclusions":["..."],"suggestions":["..."]}
            """;

    private static final String CRITIC_SYSTEM = """
            你是 IpoVideo 的 Critic。检查结果是否覆盖用户目标、结论是否具体。
            只返回严格 JSON，不要 Markdown 代码块，格式：
            {"passed":true,"issues":[]}
            """;

    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public AgentLoopService(DeepSeekClient deepSeekClient, ObjectMapper objectMapper) {
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    public AgentResult run(String userGoal, String evidence) {
        String evidenceSection = evidence == null || evidence.isBlank()
                ? ""
                : "\n视频证据（只允许基于这些证据得出结论）：\n" + evidence;
        AgentPlan plan = deepSeekClient.structuredChat(
                PLANNER_SYSTEM, "用户目标：" + userGoal + evidenceSection, AgentPlan.class);
        log.info("agent_plan tasks={}", plan.tasks() == null ? 0 : plan.tasks().size());

        List<String> critiqueIssues = List.of();
        AgentResult result = null;
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            String executorUser = "用户目标：" + userGoal
                    + evidenceSection
                    + "\n计划：" + toJson(plan)
                    + (critiqueIssues.isEmpty() ? "" : "\n上一轮批评意见：" + String.join("; ", critiqueIssues));
            result = deepSeekClient.structuredChat(EXECUTOR_SYSTEM, executorUser, AgentResult.class);

            String criticUser = "用户目标：" + userGoal
                    + evidenceSection
                    + "\nAgent 结果：" + toJson(result);
            CriticResult critic = deepSeekClient.structuredChat(CRITIC_SYSTEM, criticUser, CriticResult.class);

            List<String> citationIssues = validateEvidenceReferences(result, evidence);
            if (critic.passed() && citationIssues.isEmpty()) {
                log.info("agent_round={} passed=true", round);
                return result;
            }

            List<String> issues = new ArrayList<>();
            if (!critic.passed()) {
                issues.addAll(critic.issues() == null || critic.issues().isEmpty()
                        ? List.of("Critic 未通过但未给出具体问题")
                        : critic.issues());
            }
            issues.addAll(citationIssues);
            critiqueIssues = issues;
            log.warn("agent_round={} passed=false issues={}", round, critiqueIssues);
        }

        // 两轮都没通过：不伪造成功，任务由调用方标记失败
        throw new BusinessException(500, "分析质量未通过校验，请重新提交");
    }

    private List<String> validateEvidenceReferences(AgentResult result, String evidence) {
        Set<String> available = extractEvidenceIds(evidence);
        List<String> issues = new ArrayList<>();
        if (result == null || result.conclusions() == null || result.conclusions().isEmpty()) {
            issues.add("Agent 没有返回任何结论");
            return issues;
        }
        if (available.isEmpty()) {
            issues.add("输入证据中没有可引用的证据编号");
            return issues;
        }
        for (String conclusion : result.conclusions()) {
            Set<String> cited = extractEvidenceIds(conclusion);
            if (cited.isEmpty()) {
                issues.add("结论缺少证据引用：" + conclusion);
                continue;
            }
            for (String id : cited) {
                if (!available.contains(id)) {
                    issues.add("结论引用了不存在的证据 [" + id + "]：" + conclusion);
                }
            }
        }
        return issues;
    }

    private Set<String> extractEvidenceIds(String text) {
        Set<String> ids = new HashSet<>();
        if (text == null) {
            return ids;
        }
        Matcher matcher = Pattern.compile("\\[E(\\d+)]").matcher(text);
        while (matcher.find()) {
            ids.add("E" + matcher.group(1));
        }
        return ids;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
