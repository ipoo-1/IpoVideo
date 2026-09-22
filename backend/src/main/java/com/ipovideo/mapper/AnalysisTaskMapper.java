package com.ipovideo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ipovideo.entity.AnalysisTask;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {

    @Update("""
            UPDATE analysis_tasks
            SET status = 'RUNNING', current_stage = 'PREPARE', progress = 5,
                worker_id = #{workerId}, lease_until = #{leaseUntil},
                attempt_count = attempt_count + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND (status = 'PENDING'
                   OR (status = 'RUNNING' AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)))
            """)
    int claimTask(@Param("taskId") Long taskId,
                  @Param("workerId") String workerId,
                  @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE analysis_tasks
            SET current_stage = #{stage}, progress = #{progress},
                lease_until = #{leaseUntil}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId} AND worker_id = #{workerId} AND status = 'RUNNING'
            """)
    int updateProgress(@Param("taskId") Long taskId,
                       @Param("workerId") String workerId,
                       @Param("stage") String stage,
                       @Param("progress") Integer progress,
                       @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE analysis_tasks
            SET status = #{status}, current_stage = #{stage}, progress = #{progress}, result = #{result},
                error_message = #{errorMessage}, worker_id = NULL, lease_until = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId} AND worker_id = #{workerId} AND status = 'RUNNING'
            """)
    int completeTask(@Param("taskId") Long taskId,
                     @Param("workerId") String workerId,
                     @Param("status") String status,
                     @Param("stage") String stage,
                     @Param("progress") Integer progress,
                     @Param("result") String result,
                     @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE analysis_tasks
            SET lease_until = #{leaseUntil}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId} AND worker_id = #{workerId} AND status = 'RUNNING'
            """)
    int renewLease(@Param("taskId") Long taskId,
                   @Param("workerId") String workerId,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE analysis_tasks
            SET status = 'PENDING', current_stage = 'RECOVERY', progress = 0,
                worker_id = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND (lease_until IS NULL OR lease_until < CURRENT_TIMESTAMP)
            """)
    int requeueExpiredTask(@Param("taskId") Long taskId);
}