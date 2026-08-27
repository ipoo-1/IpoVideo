package com.ipovideo.dto;

import java.util.List;

public record AgentPlan (
    String understoodGoal,
            List<String> tasks
){

}
