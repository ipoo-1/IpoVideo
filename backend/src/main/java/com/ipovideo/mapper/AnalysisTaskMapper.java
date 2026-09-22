package com.ipovideo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ipovideo.entity.AnalysisTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {

    @Update("""
            UPDATE analysis_tasks
            SET status = 'RUNNING', current_stage = 'PREPARE', progress = 5, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId} AND status = 'PENDING'
            """)
    int claimPendingTask(@Param("taskId") Long taskId);
}
