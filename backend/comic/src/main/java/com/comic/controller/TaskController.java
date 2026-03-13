package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.TaskStatusVO;
import com.comic.entity.TaskExecution;
import com.comic.service.TaskExecutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
@Tag(name = "任务管理")
@SecurityRequirement(name = "bearerAuth")
public class TaskController {

    private final TaskExecutionService taskExecutionService;

    /**
     * 查询任务状态
     */
    @GetMapping("/{taskId}/status")
    @Operation(summary = "查询任务状态")
    public Result<TaskStatusVO> getTaskStatus(@PathVariable String taskId) {
        TaskExecution task = taskExecutionService.getTask(taskId);
        if (task == null) {
            return Result.fail(404, "任务不存在");
        }
        return Result.ok(convertToVO(task));
    }

    /**
     * 获取项目任务列表
     */
    @GetMapping("/project/{projectId}")
    @Operation(summary = "获取项目任务列表")
    public Result<List<TaskStatusVO>> getProjectTasks(@PathVariable Long projectId) {
        List<TaskExecution> tasks = taskExecutionService.getProjectTasks(projectId);
        return Result.ok(tasks.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList()));
    }

    /**
     * 取消任务
     */
    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "取消任务")
    public Result<Void> cancelTask(@PathVariable String taskId) {
        taskExecutionService.cancelTask(taskId);
        return Result.ok();
    }

    private TaskStatusVO convertToVO(TaskExecution task) {
        TaskStatusVO vo = new TaskStatusVO();
        vo.setTaskId(task.getTaskId());
        vo.setTaskType(task.getTaskType());
        vo.setProjectId(task.getProjectId());
        vo.setNodeId(task.getNodeId());
        vo.setStatus(task.getStatus());
        vo.setProgress(task.getProgress());
        vo.setResult(task.getResult());
        vo.setError(task.getError());
        vo.setStartTime(task.getStartTime());
        vo.setEndTime(task.getEndTime());
        return vo;
    }
}
