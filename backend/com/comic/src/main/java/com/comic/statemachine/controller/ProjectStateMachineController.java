package com.comic.statemachine.controller;

import com.comic.common.Result;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.enums.ProjectState;
import com.comic.statemachine.service.ProjectStateMachineService;
import com.comic.statemachine.service.StartupCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 项目状态机控制器
 * 处理项目状态转换
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ProjectStateMachineController {

    private final ProjectStateMachineService stateMachineService;
    private final ProjectRepository projectRepository;
    private final StartupCheckService startupCheckService;

    /**
     * 发送事件到状态机
     * POST /api/projects/{projectId}/event
     *
     * 请求体：
     * {
     *   "event": "GENERATE_OUTLINE",
     *   "payload": {}  // 可选，事件负载
     * }
     */
    @PostMapping("/{projectId}/event")
    @Operation(summary = "发送状态机事件", description = "向项目状态机发送事件，触发状态转换")
    public Result<Map<String, Object>> sendEvent(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {

        String eventName = (String) body.get("event");
        if (eventName == null) {
            return Result.fail("事件名称不能为空");
        }

        try {
            ProjectEventType event = ProjectEventType.valueOf(eventName);
            Map<String, Object> headers = new HashMap<>();
            headers.put("projectId", projectId);

            // 添加可选的负载
            if (body.get("payload") != null) {
                headers.put("payload", body.get("payload"));
            }

            boolean accepted = stateMachineService.sendEvent(projectId, event, headers);

            Map<String, Object> result = new HashMap<>();
            result.put("accepted", accepted);
            ProjectState currentState = stateMachineService.getCurrentState(projectId);
            result.put("currentState", currentState != null ? currentState.name() : null);

            if (!accepted) {
                return Result.fail(400, "事件被拒绝，当前状态不允许该操作");
            }

            return Result.ok(result);

        } catch (IllegalArgumentException e) {
            return Result.fail("未知的事件类型: " + eventName);
        }
    }

    /**
     * 获取项目当前状态
     * GET /api/projects/{projectId}/state
     */
    @GetMapping("/{projectId}/state")
    @Operation(summary = "获取项目状态", description = "获取项目在状态机中的当前状态")
    public Result<Map<String, Object>> getCurrentState(@PathVariable String projectId) {
        ProjectState state = stateMachineService.getCurrentState(projectId);

        Map<String, Object> result = new HashMap<>();
        result.put("state", state.name());
        result.put("phase", state.getPhase().name());
        result.put("isFailed", state.isFailed());
        result.put("isGenerating", state.isGenerating());
        result.put("isReview", state.isReview());

        return Result.ok(result);
    }

    /**
     * 重置状态机（用于测试或异常恢复）
     * POST /api/projects/{projectId}/reset-state
     */
    @PostMapping("/{projectId}/reset-state")
    @Operation(summary = "重置状态机状态", description = "重置项目状态机到指定状态（仅用于调试）")
    public Result<Void> resetState(
            @PathVariable String projectId,
            @RequestBody Map<String, String> body) {

        String stateName = body.get("state");
        if (stateName == null) {
            return Result.fail("目标状态不能为空");
        }

        try {
            ProjectState targetState = ProjectState.valueOf(stateName);
            stateMachineService.resetStateMachine(projectId, targetState);
            return Result.ok();
        } catch (IllegalArgumentException e) {
            return Result.fail("未知的状态: " + stateName);
        }
    }

    /**
     * 获取项目状态详情（兼容旧接口）
     * GET /api/projects/{projectId}/status-detail
     */
    @GetMapping("/{projectId}/status-detail")
    @Operation(summary = "获取项目状态详情", description = "返回项目当前状态的完整信息")
    public Result<ProjectStatusResponse> getStatusDetail(@PathVariable String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            return Result.fail(404, "项目不存在");
        }

        // TODO: 构建状态详情响应
        // 可以参考 ProjectController.getProjectStatusDetail 的实现
        ProjectStatusResponse response = new ProjectStatusResponse();
        // response.setProjectId(projectId);
        // response.setCurrentStep(...);
        // ...

        return Result.ok(response);
    }

    /**
     * 手动检查并修复卡死的任务（管理接口）
     * POST /api/admin/check-stuck-tasks
     */
    @PostMapping("/admin/check-stuck-tasks")
    @Operation(summary = "检查并修复卡死的任务", description = "扫描所有生成中的任务，修复超时的卡死状态")
    public Result<Map<String, Object>> checkStuckTasks() {
        int fixedCount = startupCheckService.checkAndFixStuckTasks();

        Map<String, Object> result = new HashMap<>();
        result.put("fixedCount", fixedCount);
        result.put("message", fixedCount > 0
                ? "已修复 " + fixedCount + " 个卡死的任务"
                : "没有发现卡死的任务");

        return Result.ok(result);
    }
}
