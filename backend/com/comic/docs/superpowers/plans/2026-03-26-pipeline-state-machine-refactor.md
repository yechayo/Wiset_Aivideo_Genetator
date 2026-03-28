# Pipeline 状态机重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将分散在各 Service 中的项目状态更新统一收归 PipelineService 管理，引入 Redis Pub/Sub 实现实时状态推送，消除竞态条件和非法跳转。

**Architecture:** PipelineService 作为状态转换的唯一入口，所有 Service 通过回调接口通知完成事件。Redis Pub/Sub 承担状态变更广播，ProjectSseController 订阅频道并通过 SSE 推送给前端。ProjectStatus 枚举内置 `from → event → to` 合法转换表，状态变更时做原子校验。

**Tech Stack:** Spring Boot 2.7, spring-boot-starter-data-redis, Redis Pub/Sub, SseEmitter, MyBatis-Plus

---

## 现状问题总结

| # | 问题 | 影响 |
|---|------|------|
| 1 | 5 个 Service 直接 `project.setStatus()`，绕过 PipelineService | 状态校验被跳过，PipelineService 的 `calculateNextStatus` 形同虚设 |
| 2 | `calculateNextStatus` 只看 event，不看当前状态 | 可非法跳转（如 DRAFT → PANEL_GENERATING） |
| 3 | IMAGE_REVIEW 转换存在两条并行路径（PipelineService + CharacterImageGenerationService） | 竞态条件 |
| 4 | `enrichPanelStatus` 在 GET 请求中修改 DB | 读操作产生写副作用 |
| 5 | 无实时推送，前端只能轮询 | 状态变更感知延迟不确定 |
| 6 | 异步 CompletableFuture 中的状态更新不在事务内 | 失败时状态不一致 |

## 重构后架构

```
ScriptService ──完成回调──┐
CharacterExtractService ──┤
CharacterImageGenService ─┤
PanelGenerationService ───┤         ┌──────────────┐
JobQueueService ──────────┼──▶ PipelineService ──▶ Redis Pub/Sub ──▶ ProjectSseController ──▶ 前端 SSE
                          │    (唯一状态入口)        (实时广播)
                          │         │
                          │    ┌────┴────┐
                          │    │ ProjectStatus │
                          │    │ from→event→to │
                          │    │ 合法转换表    │
                          │    └─────────┘
```

## File Structure

### 新建文件
| 文件 | 职责 |
|------|------|
| `config/RedisConfig.java` | Redis 连接配置、RedisTemplate Bean |
| `service/pipeline/ProjectStatusBroadcaster.java` | Redis Pub/Sub 发布状态变更消息 |
| `service/pipeline/StageCompletionCallback.java` | 各 Service 回调 PipelineService 的接口 |
| `controller/ProjectSseController.java` | SSE 端点，订阅 Redis 频道推送给前端 |

### 修改文件
| 文件 | 改动范围 |
|------|----------|
| `common/ProjectStatus.java` | 新增合法转换表 `allowedTransitions`，新方法 `validateTransition(from, event)` |
| `service/pipeline/PipelineService.java` | 重构 `advancePipeline`，加 from 校验；接管所有状态更新；删除 `generateAllCharacterImagesAsync` 的重复逻辑；清理 `enrichPanelStatus` 的副作用 |
| `service/script/ScriptService.java` | 删除 8 处 `project.setStatus()`，改为调用 `StageCompletionCallback` |
| `service/character/CharacterExtractService.java` | 删除 4 处 `project.setStatus()`，改为回调 |
| `service/character/CharacterImageGenerationService.java` | 删除 `checkAndAdvanceProjectStatus`，改为回调 |
| `service/panel/PanelGenerationService.java` | 删除 6 处 `project.setStatus()`，改为回调 |
| `service/job/JobQueueService.java` | 删除 `advanceProjectToPanelReview` 中的直接 setStatus，改为回调 |
| `controller/ProjectController.java` | 新增 SSE 端点路由 |
| `application.yml` | 新增 Redis 连接配置 |
| `pom.xml` | 新增 `spring-boot-starter-data-redis` 依赖 |

---

### Task 1: 添加 Redis 依赖和配置

**Files:**
- Modify: `pom.xml`
- Create: `config/RedisConfig.java`
- Modify: `application.yml`

- [ ] **Step 1: 在 pom.xml 添加 Redis 依赖**

在 `<dependencies>` 中添加：

```xml
<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

- [ ] **Step 2: 在 application.yml 添加 Redis 配置**

在 `spring:` 节点下添加：

```yaml
  # ---------- Redis ----------
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    database: 0
    timeout: 5000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 4
        min-idle: 0
```

- [ ] **Step 3: 创建 RedisConfig.java**

```java
package com.comic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }
}
```

- [ ] **Step 4: 启动验证 Redis 连接**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS，无编译错误

- [ ] **Step 5: Commit**

```bash
git add comic/pom.xml comic/src/main/resources/application.yml comic/src/main/java/com/comic/config/RedisConfig.java
git commit -m "feat: add Redis dependency and configuration"
```

---

### Task 2: 增强 ProjectStatus 枚举 — 合法转换表

**Files:**
- Modify: `common/ProjectStatus.java`

- [ ] **Step 1: 在 ProjectStatus 中添加合法转换表**

在枚举中新增静态 Map 和校验方法。在 `fromCode` 方法之后添加：

```java
/** 合法状态转换: Map<起始状态, Map<事件, 目标状态>> */
private static final Map<ProjectStatus, Map<String, ProjectStatus>> ALLOWED_TRANSITIONS;

static {
    Map<ProjectStatus, Map<String, ProjectStatus>> map = new EnumMap<>(ProjectStatus.class);

    // DRAFT → OUTLINE_GENERATING
    put(map, DRAFT, "start_script_generation", OUTLINE_GENERATING);

    // 剧本阶段内部
    put(map, OUTLINE_GENERATING, "script_generated", SCRIPT_REVIEW);
    put(map, OUTLINE_GENERATING, "script_failed", OUTLINE_GENERATING_FAILED);
    put(map, OUTLINE_REVIEW, "generate_episodes", EPISODE_GENERATING);
    put(map, OUTLINE_REVIEW, "revise_outline", OUTLINE_GENERATING);
    put(map, OUTLINE_REVIEW, "confirm_script", SCRIPT_CONFIRMED);
    put(map, EPISODE_GENERATING, "script_generated", SCRIPT_REVIEW);
    put(map, EPISODE_GENERATING, "script_failed", EPISODE_GENERATING_FAILED);
    put(map, SCRIPT_REVIEW, "generate_episodes", EPISODE_GENERATING);
    put(map, SCRIPT_REVIEW, "revise_episodes", EPISODE_GENERATING);
    put(map, SCRIPT_REVIEW, "confirm_script", SCRIPT_CONFIRMED);

    // 失败重试
    put(map, OUTLINE_GENERATING_FAILED, "retry", DRAFT);
    put(map, EPISODE_GENERATING_FAILED, "retry", OUTLINE_REVIEW);
    put(map, CHARACTER_EXTRACTING_FAILED, "retry", SCRIPT_CONFIRMED);
    put(map, IMAGE_GENERATING_FAILED, "retry", CHARACTER_CONFIRMED);
    put(map, PANEL_GENERATING_FAILED, "retry", ASSET_LOCKED);

    // 剧本 → 角色
    put(map, SCRIPT_CONFIRMED, "start_character_extraction", CHARACTER_EXTRACTING);

    // 角色阶段
    put(map, CHARACTER_EXTRACTING, "characters_extracted", CHARACTER_REVIEW);
    put(map, CHARACTER_EXTRACTING, "characters_failed", CHARACTER_EXTRACTING_FAILED);
    put(map, CHARACTER_REVIEW, "confirm_characters", CHARACTER_CONFIRMED);

    // 角色 → 图像
    put(map, CHARACTER_CONFIRMED, "start_image_generation", IMAGE_GENERATING);

    // 图像阶段
    put(map, IMAGE_GENERATING, "images_generated", IMAGE_REVIEW);
    put(map, IMAGE_GENERATING, "images_failed", IMAGE_GENERATING_FAILED);
    put(map, IMAGE_REVIEW, "confirm_images", ASSET_LOCKED);

    // 素材 → 分镜
    put(map, ASSET_LOCKED, "start_panels", PANEL_GENERATING);

    // 分镜阶段
    put(map, PANEL_GENERATING, "panels_generated", PANEL_REVIEW);
    put(map, PANEL_GENERATING, "panels_failed", PANEL_GENERATING_FAILED);
    put(map, PANEL_REVIEW, "confirm_panels", PANEL_REVIEW);   // 逐集确认，保持 REVIEW
    put(map, PANEL_REVIEW, "all_panels_confirmed", PRODUCING);
    put(map, PANEL_REVIEW, "revise_panels", PANEL_GENERATING);
    put(map, PANEL_GENERATING_FAILED, "retry", ASSET_LOCKED);

    // 生产 → 完成
    put(map, PRODUCING, "production_completed", COMPLETED);

    ALLOWED_TRANSITIONS = Collections.unmodifiableMap(map);
}

private static void put(Map<ProjectStatus, Map<String, ProjectStatus>> map,
                        ProjectStatus from, String event, ProjectStatus to) {
    map.computeIfAbsent(from, k -> new HashMap<>()).put(event, to);
}
```

- [ ] **Step 2: 添加状态校验方法**

```java
/**
 * 校验状态转换是否合法，返回目标状态；不合法返回 null。
 */
public static ProjectStatus resolveTransition(ProjectStatus from, String event) {
    if (from == null || event == null) return null;
    Map<String, ProjectStatus> events = ALLOWED_TRANSITIONS.get(from);
    return events != null ? events.get(event) : null;
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add comic/src/main/java/com/comic/common/ProjectStatus.java
git commit -m "feat: add state transition validation table to ProjectStatus"
```

---

### Task 3: 创建 StageCompletionCallback 接口

**Files:**
- Create: `service/pipeline/StageCompletionCallback.java`

- [ ] **Step 1: 创建回调接口**

```java
package com.comic.service.pipeline;

/**
 * 各业务 Service 完成阶段任务后，通过此接口通知 PipelineService 推进状态。
 * 所有状态变更必须经过此回调，禁止直接 project.setStatus()。
 */
public interface StageCompletionCallback {

    /**
     * 通知阶段完成，触发状态推进。
     *
     * @param projectId 项目 ID
     * @param event     事件名（对应 ProjectStatus.ALLOWED_TRANSITIONS 中的 key）
     */
    void onStageComplete(String projectId, String event);

    /**
     * 通知阶段失败。
     *
     * @param projectId 项目 ID
     * @param event     失败事件名（如 "script_failed"）
     */
    void onStageFailed(String projectId, String event);
}
```

- [ ] **Step 2: Commit**

```bash
git add comic/src/main/java/com/comic/service/pipeline/StageCompletionCallback.java
git commit -m "feat: add StageCompletionCallback interface"
```

---

### Task 4: 创建 ProjectStatusBroadcaster (Redis Pub/Sub)

**Files:**
- Create: `service/pipeline/ProjectStatusBroadcaster.java`

- [ ] **Step 1: 创建广播服务**

```java
package com.comic.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 通过 Redis Pub/Sub 广播项目状态变更。
 * 前端通过 SSE 订阅 project:status:{projectId} 频道获取实时推送。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectStatusBroadcaster {

    private static final String CHANNEL_PREFIX = "project:status:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 发布状态变更消息。
     *
     * @param projectId 项目 ID
     * @param from      变更前状态
     * @param to        变更后状态
     */
    public void broadcast(String projectId, String from, String to) {
        try {
            Map<String, String> message = new HashMap<>();
            message.put("projectId", projectId);
            message.put("from", from);
            message.put("to", to);
            message.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + projectId, json);
            log.info("Status broadcast: projectId={}, {} -> {}", projectId, from, to);
        } catch (Exception e) {
            log.warn("Failed to broadcast status change: projectId={}, error={}", projectId, e.getMessage());
            // 广播失败不影响主流程，前端可通过轮询兜底
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add comic/src/main/java/com/comic/service/pipeline/ProjectStatusBroadcaster.java
git commit -m "feat: add ProjectStatusBroadcaster with Redis Pub/Sub"
```

---

### Task 5: 创建 ProjectSseController (SSE 端点)

**Files:**
- Create: `controller/ProjectSseController.java`

- [ ] **Step 1: 创建 SSE Controller**

```java
package com.comic.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "项目状态实时推送")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class ProjectSseController {

    private final RedisMessageListenerContainer redisContainer;
    private final ObjectMapper objectMapper;

    /** projectId → 活跃的 SSE 连接集合 */
    private final ConcurrentHashMap<String, CopyOnWriteArraySet<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * 订阅项目状态变更（SSE）
     * 前端: new EventSource('/api/projects/{projectId}/status/stream')
     */
    @GetMapping(value = "/{projectId}/status/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅项目状态实时变更")
    public SseEmitter streamProjectStatus(@PathVariable String projectId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        emitters.computeIfAbsent(projectId, k -> new CopyOnWriteArraySet<>()).add(emitter);

        // 首次连接时发送当前状态（由前端通过 GET /status 获取，SSE 只负责增量推送）
        String channel = "project:status:" + projectId;

        // 注册 Redis 监听（如果该 channel 尚未注册）
        MessageListener listener = (message, pattern) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = objectMapper.readValue(message.getBody(), Map.class);
                sendToEmitters(projectId, data);
            } catch (Exception e) {
                log.warn("Failed to parse Redis message for project {}", projectId, e);
            }
        };

        redisContainer.addMessageListener(listener, new ChannelTopic(channel));

        emitter.onCompletion(() -> {
            removeEmitter(projectId, emitter);
            redisContainer.removeMessageListener(listener);
            cleanupIfEmpty(projectId);
        });
        emitter.onTimeout(() -> {
            removeEmitter(projectId, emitter);
            redisContainer.removeMessageListener(listener);
            cleanupIfEmpty(projectId);
        });
        emitter.onError(e -> {
            removeEmitter(projectId, emitter);
            redisContainer.removeMessageListener(listener);
            cleanupIfEmpty(projectId);
        });

        return emitter;
    }

    private void sendToEmitters(String projectId, Map<String, Object> data) {
        CopyOnWriteArraySet<SseEmitter> set = emitters.get(projectId);
        if (set == null) return;
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name("status-change").data(data));
            } catch (IOException e) {
                removeEmitter(projectId, emitter);
            }
        }
    }

    private void removeEmitter(String projectId, SseEmitter emitter) {
        CopyOnWriteArraySet<SseEmitter> set = emitters.get(projectId);
        if (set != null) set.remove(emitter);
    }

    private void cleanupIfEmpty(String projectId) {
        CopyOnWriteArraySet<SseEmitter> set = emitters.get(projectId);
        if (set != null && set.isEmpty()) {
            emitters.remove(projectId);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add comic/src/main/java/com/comic/controller/ProjectSseController.java
git commit -m "feat: add ProjectSseController for real-time status push via SSE"
```

---

### Task 6: 重构 PipelineService — 统一状态入口

这是核心 Task。PipelineService 实现 `StageCompletionCallback`，接管所有状态更新逻辑。

**Files:**
- Modify: `service/pipeline/PipelineService.java`

- [ ] **Step 1: 让 PipelineService 实现 StageCompletionCallback**

修改类声明，注入 `ProjectStatusBroadcaster`：

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService implements StageCompletionCallback {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final PanelRepository panelRepository;
    private final CharacterRepository characterRepository;
    private final ScriptService scriptService;
    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;
    private final ProjectStatusBroadcaster broadcaster;

    // ... 保留原有字段 ...
```

- [ ] **Step 2: 重写 advancePipeline — 加 from 校验**

替换现有的 `advancePipeline` 方法和 `calculateNextStatus` 方法：

```java
// ==================== Pipeline 状态转换（唯一入口）====================

@Override
@Transactional
public void onStageComplete(String projectId, String event) {
    advancePipeline(projectId, event);
}

@Override
@Transactional
public void onStageFailed(String projectId, String event) {
    advancePipeline(projectId, event);
}

@Transactional
public void advancePipeline(String projectId, String event) {
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null) {
        throw new BusinessException("项目不存在");
    }

    ProjectStatus current = ProjectStatus.fromCode(project.getStatus());
    ProjectStatus next = ProjectStatus.resolveTransition(current, event);

    if (next == null) {
        log.warn("Illegal transition rejected: projectId={}, current={}, event={}", projectId, current, event);
        throw new BusinessException("非法状态转换: " + current.getCode() + " + " + event);
    }

    String oldStatus = project.getStatus();
    project.setStatus(next.getCode());
    projectRepository.updateById(project);
    log.info("Pipeline advanced: projectId={}, {} -> {} (event={})", projectId, oldStatus, next.getCode(), event);

    // 广播状态变更
    broadcaster.broadcast(projectId, oldStatus, next.getCode());

    // 触发下一阶段
    triggerNextStage(projectId, next);
}

@Transactional
public void advancePipeline(String projectId, String direction, String event) {
    if ("backward".equals(direction)) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) throw new BusinessException("项目不存在");
        rollbackPipeline(project);
    } else {
        advancePipeline(projectId, event);
    }
}

/** 保留旧签名的兼容方法 — 不推荐使用，新代码统一用 advancePipeline(projectId, event) */
@Deprecated
@Transactional
public void advancePipeline(String projectId, String direction, String event) {
    advancePipeline(projectId, direction, event);
}
```

> 注意：原有 `calculateNextStatus` 方法直接删除，其逻辑已被 `ProjectStatus.resolveTransition` 取代。

- [ ] **Step 3: 修改 rollbackPipeline 也发广播**

在 `rollbackPipeline` 方法中 `projectRepository.updateById(project)` 之后添加：

```java
broadcaster.broadcast(projectId, current.getCode(), previous.getCode());
```

- [ ] **Step 4: 删除 generateAllCharacterImagesAsync 中的重复状态更新**

`PipelineService.generateAllCharacterImagesAsync` 方法中，成功后设置 `IMAGE_REVIEW` 和失败后设置 `IMAGE_GENERATING_FAILED` 的代码块（约 line 747-759），替换为回调：

```java
// 成功时
callback.onStageComplete(projectId, "images_generated");

// 失败时
callback.onStageFailed(projectId, "images_failed");
```

但由于这是异步线程，不能直接注入 `this`（会绕过代理），需要改为：

```java
private void generateAllCharacterImagesAsync(String projectId) {
    CompletableFuture.runAsync(() -> {
        try {
            // ... 原有生成逻辑不变 ...

            if (project != null && ProjectStatus.IMAGE_GENERATING.getCode().equals(project.getStatus())) {
                // 通过广播触发，而非直接 setStatus
                // 由于在异步线程中，使用 PipelineService 的非代理引用不安全
                // 改为直接调 onStageComplete（已标 @Transactional(propagation = REQUIRES_NEW) 或使用 TransactionTemplate）
                try {
                    pipelineServiceSelf.advancePipeline(projectId, "images_generated");
                } catch (Exception e) {
                    log.warn("Failed to advance status after image generation: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Character image batch generation failed: projectId={}", projectId, e);
            try {
                pipelineServiceSelf.advancePipeline(projectId, "images_failed");
            } catch (Exception ex) {
                log.warn("Failed to set failed status: {}", ex.getMessage());
            }
        }
    });
}
```

在类中添加自引用：

```java
@Lazy
@Autowired
private PipelineService pipelineServiceSelf;
```

- [ ] **Step 5: 清理 enrichPanelStatus 的副作用**

将 `enrichPanelStatus` 中修改 episode status 的代码（约 line 558-569，`isStaleGenerating` 恢复逻辑）移到一个独立的定时任务或启动恢复流程中，不在 GET 请求中修改数据。简单做法：先直接删除这段副作用代码，后续单独加恢复任务。

删除 `enrichPanelStatus` 方法中的以下代码块：

```java
// 删除这段 —— 不应在读操作中修改数据库
if (isStaleGenerating(failedEpisode)) {
    failedEpisode.setStatus("PANEL_FAILED");
    // ...
    episodeRepository.updateById(failedEpisode);
}
```

- [ ] **Step 6: 删除 enrichProducingStatus 中的自动完成逻辑**

`enrichProducingStatus` 中 `completedPanels == totalPanels` 时直接改 project status 为 COMPLETED（约 line 438-446）。这也是读操作写副作用的反模式。改为：

```java
// 只返回信息，不修改数据库。前端/定时任务负责触发完成。
if (completedPanels == totalPanels) {
    dto.setStatusCode(ProjectStatus.COMPLETED.getCode());
    dto.setStatusDescription("Completed");
    dto.setGenerating(false);
    dto.setProductionProgress(100);
    return;
}
```

> 注意：PanelProductionService 中最后一个 panel 完成时应回调 `onStageComplete(projectId, "production_completed")`，这将在后续 Task 8 中处理。

- [ ] **Step 7: 编译验证**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add comic/src/main/java/com/comic/service/pipeline/PipelineService.java
git commit -m "refactor: PipelineService becomes the single entry point for all status changes"
```

---

### Task 7: 重构 ScriptService — 删除直接 setStatus

**Files:**
- Modify: `service/script/ScriptService.java`

- [ ] **Step 1: 注入 StageCompletionCallback**

```java
private final PipelineService pipelineService;  // 已有，保留
```

ScriptService 已经注入了 `pipelineService`，无需额外注入。

- [ ] **Step 2: 替换所有 project.setStatus 调用**

**generateScriptOutline 方法（约 line 132-183）：**

```java
// 原来: project.setStatus(STATUS_OUTLINE_GENERATING);
// 改为: pipelineService.advancePipeline(projectId, "start_script_generation");
// 注意：advancePipeline 内部已经会校验 from→to，所以这里需要先推进状态再执行逻辑

// 实际上 generateScriptOutline 是被 triggerNextStage 触发的，
// triggerNextStage 已经设置了状态为 OUTLINE_GENERATING。
// 所以方法内部的 setStatus 是冗余的。直接删除即可。
```

逐个替换清单：

| 原代码 | 替换为 |
|--------|--------|
| `project.setStatus(STATUS_OUTLINE_GENERATING)` (line 132) | **删除**（已由 triggerNextStage 设置） |
| `project.setStatus(STATUS_OUTLINE_REVIEW)` (line 175) | `pipelineService.advancePipeline(projectId, "script_generated")` |
| `project.setStatus(STATUS_OUTLINE_FAILED)` (line 182) | `pipelineService.advancePipeline(projectId, "script_failed")` |
| `project.setStatus(STATUS_EPISODE_GENERATING)` (line 212) | **删除**（确认生成前状态已正确） |
| `project.setStatus(STATUS_SCRIPT_REVIEW)` (line 249) | `pipelineService.advancePipeline(projectId, "script_generated")` |
| `project.setStatus(STATUS_EPISODE_FAILED)` (line 257) | `pipelineService.advancePipeline(projectId, "script_failed")` |
| `project.setStatus(STATUS_SCRIPT_CONFIRMED)` (line 349, 356) | `pipelineService.advancePipeline(projectId, "confirm_script")` |
| `project.setStatus(STATUS_OUTLINE_GENERATING)` (line 828) | `pipelineService.advancePipeline(projectId, "start_script_generation")` |
| `project.setStatus(STATUS_OUTLINE_REVIEW)` (line 867) | `pipelineService.advancePipeline(projectId, "script_generated")` |
| `project.setStatus(STATUS_OUTLINE_FAILED)` (line 877) | `pipelineService.advancePipeline(projectId, "script_failed")` |

> 注意：由于 `advancePipeline` 内部会重新查 project 并校验状态，如果当前状态不匹配会抛异常。需要确认 `generateScriptOutline` 调用时 project 状态已经是正确的。查看 `triggerNextStage`，它在调用 `scriptService.generateScriptOutline` 之前已经将状态设为 `OUTLINE_GENERATING`，所以方法内部不需要再设。但方法内直接 `projectRepository.updateById(project)` 更新的是方法开头查到的 project 对象引用，而 `advancePipeline` 内部会重新查一次 project。这里需要注意避免 **同一事务内两次查到的 project 对象不一致** 的问题。由于都在同一事务内，MyBatis 一级缓存会保证拿到同一对象，所以是安全的。

- [ ] **Step 3: 删除 confirmChapter 中的 pipelineService.advancePipeline 调用**

`confirmChapter` 方法（约 line 369）中已有 `pipelineService.advancePipeline(projectId, "start_character_extraction")`。当所有 chapter 确认完毕后，这会自动触发角色提取。保留此调用。

- [ ] **Step 4: 编译验证**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add comic/src/main/java/com/comic/service/script/ScriptService.java
git commit -m "refactor: ScriptService delegates all status changes to PipelineService"
```

---

### Task 8: 重构 CharacterExtractService — 删除直接 setStatus

**Files:**
- Modify: `service/character/CharacterExtractService.java`

- [ ] **Step 1: 注入 PipelineService**

```java
private final PipelineService pipelineService;
```

更新构造函数（`@RequiredArgsConstructor` 会自动处理，但需确保字段声明顺序和构造函数参数一致）。

- [ ] **Step 2: 替换 extractCharacters 方法中的 setStatus**

```java
// 删除 line 50-51: project.setStatus(CHARACTER_EXTRACTING) + projectRepository.updateById
// 原因：状态已由 triggerNextStage 设置

// 替换 line 82-83:
// 原: project.setStatus(ProjectStatus.CHARACTER_REVIEW.getCode());
//     projectRepository.updateById(project);
// 改: pipelineService.advancePipeline(projectId, "characters_extracted");

// 替换 line 90-91:
// 原: project.setStatus(ProjectStatus.CHARACTER_EXTRACTING_FAILED.getCode());
//     projectRepository.updateById(project);
// 改: pipelineService.advancePipeline(projectId, "characters_failed");
```

- [ ] **Step 3: 替换 confirmCharacters 方法中的 setStatus**

```java
// 替换 line 114-115:
// 原: project.setStatus(ProjectStatus.CHARACTER_CONFIRMED.getCode());
//     projectRepository.updateById(project);
// 改: pipelineService.advancePipeline(projectId, "confirm_characters");
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add comic/src/main/java/com/comic/service/character/CharacterExtractService.java
git commit -m "refactor: CharacterExtractService delegates status changes to PipelineService"
```

---

### Task 9: 重构 CharacterImageGenerationService — 删除直接 setStatus

**Files:**
- Modify: `service/character/CharacterImageGenerationService.java`

- [ ] **Step 1: 注入 PipelineService**

```java
private final PipelineService pipelineService;
```

- [ ] **Step 2: 替换 checkAndAdvanceProjectStatus 方法**

将整个 `checkAndAdvanceProjectStatus` 方法替换为：

```java
private void checkAndAdvanceProjectStatus(Character character) {
    String projectId = character.getProjectId();
    Project project = projectRepository.findByProjectId(projectId);
    if (project == null || !ProjectStatus.IMAGE_GENERATING.getCode().equals(project.getStatus())) {
        return;
    }

    List<Character> allCharacters = characterRepository.findByProjectId(projectId);
    boolean allDone = allCharacters.stream().allMatch(c -> isCharacterImageComplete(c));

    if (allDone) {
        log.info("所有角色图片生成完成: projectId={}", projectId);
        pipelineService.advancePipeline(projectId, "images_generated");
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add comic/src/main/java/com/comic/service/character/CharacterImageGenerationService.java
git commit -m "refactor: CharacterImageGenerationService delegates status changes to PipelineService"
```

---

### Task 10: 重构 PanelGenerationService — 删除直接 setStatus

**Files:**
- Modify: `service/panel/PanelGenerationService.java`

- [ ] **Step 1: 确认 PipelineService 已注入**

PanelGenerationService 已经注入了 `pipelineService`，无需额外操作。

- [ ] **Step 2: 替换所有 project.setStatus 调用**

| 原代码位置 | 原代码 | 替换为 |
|-----------|--------|--------|
| line 287 | `project.setStatus(PANEL_GENERATING)` | `pipelineService.advancePipeline(projectId, "start_panels")` |
| line 308 | `project.setStatus(PANEL_GENERATING)` | `pipelineService.advancePipeline(projectId, "start_panels")` |
| line 337 | `project.setStatus(PANEL_REVIEW)` | `pipelineService.advancePipeline(projectId, "panels_generated")` |
| line 343 | `project.setStatus(PANEL_GENERATING)` | `pipelineService.advancePipeline(projectId, "start_panels")` |
| line 485 | `project.setStatus(PANEL_GENERATING_FAILED)` | `pipelineService.advancePipeline(projectId, "panels_failed")` |

- [ ] **Step 3: 保留已有的 pipelineService.advancePipeline 调用**

以下调用已经正确使用 PipelineService，保留不变：
- line 317: `pipelineService.advancePipeline(projectId, "panel_generated")`
- line 364: `pipelineService.advancePipeline(projectId, "start_production")`
- line 447: `pipelineService.advancePipeline(projectId, "panel_generated")`

- [ ] **Step 4: 编译验证**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add comic/src/main/java/com/comic/service/panel/PanelGenerationService.java
git commit -m "refactor: PanelGenerationService delegates status changes to PipelineService"
```

---

### Task 11: 重构 JobQueueService — 删除直接 setStatus

**Files:**
- Modify: `service/job/JobQueueService.java`

- [ ] **Step 1: 注入 PipelineService**

```java
private final PipelineService pipelineService;
```

- [ ] **Step 2: 替换 advanceProjectToPanelReview 中的直接 setStatus**

```java
// 原 (line 210-211):
//   project.setStatus("PANEL_REVIEW");
//   projectRepository.updateById(project);
// 改为:
pipelineService.advancePipeline(projectId, "panels_generated");
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add comic/src/main/java/com/comic/service/job/JobQueueService.java
git commit -m "refactor: JobQueueService delegates status changes to PipelineService"
```

---

### Task 12: 验证 — 确认无残留直接 setStatus

**Files:** 全局搜索验证

- [ ] **Step 1: 全局搜索 Service 层中残留的 project.setStatus**

Run: `grep -rn "project\.setStatus\|project\.getStatus" comic/src/main/java/com/comic/service/ --include="*.java" | grep -v PipelineService`

Expected: 无结果（或仅包含状态读取 `getStatus`，不含 `setStatus`）

- [ ] **Step 2: 全局搜索 projectRepository.updateById 中隐含的状态更新**

Run: `grep -rn "projectRepository\.updateById" comic/src/main/java/com/comic/service/ --include="*.java" | grep -v PipelineService`

Expected: 仅出现在非状态更新场景（如 updateProject、logicalDeleteProject）

- [ ] **Step 3: 如果发现残留，修复并提交**

- [ ] **Step 4: 最终编译和启动验证**

Run: `mvn compile -pl comic -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit (if any fixes)**

```bash
git add -A
git commit -m "refactor: remove remaining direct setStatus calls from services"
```

---

### Task 13: (可选) TokenBlacklistService 迁移到 Redis

**Files:**
- Modify: `security/TokenBlacklistService.java`

> 这是额外优化。如果当前 Token 黑名单用 Caffeine 已足够，可跳过此 Task。

- [ ] **Step 1: 替换 TokenBlacklistService 实现为 Redis 版本**

用文件底部注释中已有的 Redis 版本替换 Caffeine 实现（见 TokenBlacklistService.java:50-70）。

- [ ] **Step 2: Commit**

```bash
git add comic/src/main/java/com/comic/security/TokenBlacklistService.java
git commit -m "refactor: migrate TokenBlacklistService from Caffeine to Redis"
```

---

## 前端对接说明

重构完成后，前端需要做的改动：

1. **SSE 订阅（推荐）**: 在项目详情页建立 EventSource 连接
   ```javascript
   const es = new EventSource(`/api/projects/${projectId}/status/stream`, {
     headers: { 'Authorization': `Bearer ${token}` }
   });
   es.addEventListener('status-change', (e) => {
     const data = JSON.parse(e.data);
     // data.from, data.to, data.projectId, data.timestamp
     refreshProjectStatus();
   });
   ```

2. **轮询兜底（降级方案）**: 保持现有的 `GET /api/projects/{projectId}/status` 轮询作为 SSE 断连时的降级。

3. **状态码无变化**: 所有状态码字符串不变，前端无需修改状态判断逻辑。

## 风险和注意事项

1. **循环依赖**: PipelineService 依赖 ScriptService/CharacterExtractService 等，而这些 Service 反过来依赖 PipelineService。Spring 的 `@Lazy` 注解已用于 PanelGenerationService，其他新增依赖也需要加 `@Lazy`。

2. **事务边界**: 异步线程（CompletableFuture）中的回调不参与外层事务。PipelineService 的 `advancePipeline` 每次独立开事务，所以异步回调是安全的。

3. **Redis 不可用降级**: `ProjectStatusBroadcaster.broadcast` 中 catch 了异常并仅 log.warn，不会阻塞主流程。前端仍有轮询兜底。

4. **SSE + JWT**: SSE (EventSource API) 不支持自定义 Header。需要前端通过 URL query param 传 token，或在 Redis Pub/Sub 不做鉴权（因为只有项目状态，非敏感数据）。当前实现依赖 Spring Security 的 filter chain 对 SSE 端点做鉴权，如果前端 EventSource 无法带 Header，需要在 `SecurityConfig` 中放行该端点或改用 token query param。