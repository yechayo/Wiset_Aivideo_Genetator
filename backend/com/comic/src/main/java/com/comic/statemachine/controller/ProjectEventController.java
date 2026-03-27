package com.comic.statemachine.controller;

import com.comic.statemachine.sse.ProjectSseBroadcaster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.TimeUnit;

/**
 * 项目事件 SSE 控制器
 * 提供前端订阅项目状态变更的端点
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "项目事件", description = "SSE 实时推送")
@SecurityRequirement(name = "bearerAuth")
public class ProjectEventController {

    private final ProjectSseBroadcaster broadcaster;

    /**
     * 订阅项目事件
     *
     * 前端使用方式：
     * const eventSource = new EventSource('/api/events/projects/{projectId}', {
     *     headers: { 'Authorization': 'Bearer ' + token }
     * });
     * eventSource.addEventListener('message', (e) => {
     *     const data = JSON.parse(e.data);
     *     // data.type: 'state_change' | 'progress' | 'failure' | 'task_start' | 'task_complete' | 'heartbeat'
     * });
     *
     * @param projectId 项目 ID
     * @return SSE Emitter
     */
    @GetMapping(value = "/projects/{projectId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅项目事件", description = "通过 SSE 实时接收项目状态变更、进度更新、失败等事件")
    public SseEmitter subscribeProjectEvents(
            @Parameter(description = "项目 ID", required = true)
            @PathVariable String projectId) {

        // 创建 SSE 连接，超时时间 30 分钟
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30));

        // 注册到广播器
        broadcaster.register(projectId, emitter);

        return emitter;
    }
}
