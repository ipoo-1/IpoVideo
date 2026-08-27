package com.ipovideo.dto;

import java.util.List;

public record AgentResult(
        String title,
        List<String> conclusions,
        List<String> suggestions
) {
}