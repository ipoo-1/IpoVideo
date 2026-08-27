package com.ipovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ipovideo.common.BusinessException;
import com.ipovideo.config.RedisKeys;
import com.ipovideo.dto.AnalysisTaskMsg;
import com.ipovideo.dto.CreateTaskRequest;
import com.ipovideo.dto.TaskStatus;
import com.ipovideo.dto.TaskView;
import com.ipovideo.entity.AnalysisTask;
import com.ipovideo.entity.MediaFile;
import com.ipovideo.mapper.AnalysisTaskMapper;
import com.ipovideo.mapper.MediaFileMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 分析任务服务：创建任务后立即返回，真正的工作交给 RocketMQ 消费者里的 TaskWorker。
 */
@Service
public class TaskService {

    private final AnalysisTaskMapper taskMapper;
    private final MediaFileMapper mediaFileMapper;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final String analysisTopic;

    public TaskService(AnalysisTaskMapper taskMapper,
                       MediaFileMapper mediaFileMapper,
                       StringRedisTemplate redisTemplate,
                       RocketMQTemplate rocketMQTemplate,
                       @Value("${rocketmq.topic.video-analysis:video-analysis-topic}") String analysisTopic) {
        this.taskMapper = taskMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.analysisTopic = analysisTopic;
    }

    public TaskView create(Long userId, CreateTaskRequest request) {
        MediaFile media = mediaFileMapper.selectById(request.mediaId());
        if (media == null) {
            throw new BusinessException(404, "视频不存在");
        }
        if (!media.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权使用该视频");
        }

        // Redis 分布式锁：多实例下也能拦住同一视频的重复提交
        String lockKey = RedisKeys.taskLockKey(request.mediaId());
        String owner = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, owner, Duration.ofSeconds(5));
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException(409, "该视频正在分析中，请勿重复提交");
        }
        try {
            // 数据库再查一次进行中的任务，作为第二道防线
            QueryWrapper<AnalysisTask> dupQuery = new QueryWrapper<>();
            dupQuery.eq("media_id", request.mediaId())
                    .eq("user_id", userId)
                    .in("status", TaskStatus.PENDING.name(), TaskStatus.RUNNING.name());
            if (taskMapper.selectCount(dupQuery) > 0) {
                throw new BusinessException(409, "该视频已有进行中的分析任务");
            }

            AnalysisTask task = new AnalysisTask();
            task.setMediaId(request.mediaId());
            task.setUserId(userId);
            task.setGoal(request.goal().trim());
            task.setStatus(TaskStatus.PENDING.name());
            task.setProgress(0);
            task.setCreatedAt(LocalDateTime.now());
            task.setUpdatedAt(LocalDateTime.now());
            taskMapper.insert(task);

            // 提交即返回：消息投递到 RocketMQ，由消费者异步执行
            try {
                rocketMQTemplate.convertAndSend(analysisTopic,
                        new AnalysisTaskMsg(task.getId(), request.mediaId(), userId, task.getGoal()));
            } catch (RuntimeException ex) {
                // 投递失败不能让任务静默丢失：标记失败，前端能查到原因
                task.setStatus(TaskStatus.FAILED.name());
                task.setErrorMessage("任务投递失败：" + ex.getMessage());
                task.setUpdatedAt(LocalDateTime.now());
                taskMapper.updateById(task);
                throw new BusinessException(500, "任务投递失败，请稍后重试");
            }
            return TaskView.from(task);
        } finally {
            // 只有锁还是自己的才删除，避免误删别人重新拿到的锁
            if (owner.equals(redisTemplate.opsForValue().get(lockKey))) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    public TaskView getForUser(Long taskId, Long userId) {
        return TaskView.from(requireTask(taskId, userId));
    }

    public void checkAccess(Long taskId, Long userId) {
        requireTask(taskId, userId);
    }

    private AnalysisTask requireTask(Long taskId, Long userId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在");
        }
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权查看该任务");
        }
        return task;
    }
    public List<TaskView> listForUser(Long userId) {
        QueryWrapper<AnalysisTask> query = new QueryWrapper<>();
        query.eq("user_id", userId).orderByDesc("created_at");
        List<AnalysisTask> tasks = taskMapper.selectList(query);
        return tasks.stream().map(TaskView::from).toList();
    }
}
