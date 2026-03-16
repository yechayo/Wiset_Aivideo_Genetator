package com.comic.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.comic.entity.TaskExecution;
import com.comic.mapper.TaskExecutionMapper;
import com.google.gson.Gson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutionService {

    private final TaskExecutionMapper taskExecutionMapper;
    private final Gson gson = new Gson();

    /**
     * 创建任务
     */
    public TaskExecution createTask(String taskType, Long projectId, String nodeId) {
        TaskExecution task = new TaskExecution();
        task.setTaskId(UUID.randomUUID().toString());
        task.setTaskType(taskType);
        task.setProjectId(projectId);
        task.setNodeId(nodeId);
        task.setStatus("pending");
        task.setProgress(0);
        task.setStartTime(LocalDateTime.now());
        taskExecutionMapper.insert(task);
        return task;
    }

    /**
     * 更新任务状态为运行中
     */
    public void updateTaskToRunning(String taskId) {
        TaskExecution task = getTask(taskId);
        if (task != null) {
            task.setStatus("running");
            task.setStartTime(LocalDateTime.now());
            taskExecutionMapper.updateById(task);
        }
    }

    /**
     * 更新任务进度
     */
    public void updateTaskProgress(String taskId, Integer progress) {
        TaskExecution task = getTask(taskId);
        if (task != null) {
            task.setProgress(progress);
            taskExecutionMapper.updateById(task);
        }
    }

    /**
     * 完成任务
     */
    public void completeTask(String taskId, Object result) {
        TaskExecution task = getTask(taskId);
        if (task != null) {
            task.setStatus("completed");
            task.setProgress(100);
            task.setResult(gson.toJson(result));
            task.setEndTime(LocalDateTime.now());
            taskExecutionMapper.updateById(task);
        }
    }

    /**
     * 失败任务
     */
    public void failTask(String taskId, String error) {
        TaskExecution task = getTask(taskId);
        if (task != null) {
            task.setStatus("failed");
            task.setError(error);
            task.setEndTime(LocalDateTime.now());
            taskExecutionMapper.updateById(task);
        }
    }

    /**
     * 取消任务
     */
    public void cancelTask(String taskId) {
        TaskExecution task = getTask(taskId);
        if (task != null) {
            task.setStatus("cancelled");
            task.setEndTime(LocalDateTime.now());
            taskExecutionMapper.updateById(task);
        }
    }

    /**
     * 获取任务
     */
    public TaskExecution getTask(String taskId) {
        return taskExecutionMapper.selectOne(
                new QueryWrapper<TaskExecution>().eq("task_id", taskId)
        );
    }

    /**
     * 获取项目任务列表
     */
    public List<TaskExecution> getProjectTasks(Long projectId) {
        return taskExecutionMapper.selectList(
                new QueryWrapper<TaskExecution>().eq("project_id", projectId)
        );
    }

    /**
     * 获取运行中的任务
     */
    public List<TaskExecution> getRunningTasks() {
        return taskExecutionMapper.selectList(
                new QueryWrapper<TaskExecution>().eq("status", "running")
        );
    }
}
