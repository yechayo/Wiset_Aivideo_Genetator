# Episode & Panel API 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重写 EpisodeController 为纯 CRUD，新建 PanelController 承载全部视频生产逻辑（22 个接口），删除 StoryController 和 GridSplitService，标记旧生产方法为 @Deprecated。

**Architecture:** EpisodeController 嵌套在 `/api/projects/{projectId}/episodes`，PanelController 嵌套在 `/api/projects/{projectId}/episodes/{episodeId}/panels`。新增 EpisodeService、PanelService 处理 CRUD，新增 ComicGenerationService 处理四宫格 AI 融合。StoryboardService 保留，由 PanelController 调用。旧的生产流程（GridSplitService、手动融合上传）废弃删除。

**Tech Stack:** Spring Boot, MyBatis-Plus, Lombok, Swagger/OpenAPI

**Spec 文档:** `docs/superpowers/specs/2026-03-25-episode-panel-api-design.md`

**源码基础路径:** `backend/com/comic/src/main/java/com/comic/`

---

## 文件结构总览

### 修改文件
| 文件 | 变更 |
|------|------|
| `controller/EpisodeController.java` | 重写为 5 个 CRUD 接口 |
| `repository/EpisodeRepository.java` | 新增 `findByProjectIdAndId`、`findPageByProjectId` |
| `repository/PanelRepository.java` | 新增 `findByEpisodeIdAndId` |
| `dto/response/PanelBackgroundResponse.java` | 新增 `panelId` 字段，适配新模型 |
| `service/production/PanelProductionService.java` | 新增 panelId 系列方法，标记旧方法为 @Deprecated |
| `service/production/EpisodeProductionService.java` | 移除 GridSplitService 引用，标记为 @Deprecated |

### 新建文件
| 文件 | 职责 |
|------|------|
| `service/episode/EpisodeService.java` | Episode CRUD + 分页 |
| `service/panel/PanelService.java` | Panel CRUD |
| `service/production/ComicGenerationService.java` | 四宫格 AI 融合生成 |
| `controller/PanelController.java` | 23 个接口（CRUD + 生产） |
| `dto/request/EpisodeCreateRequest.java` | 创建剧集请求 |
| `dto/request/EpisodeUpdateRequest.java` | 更新剧集请求 |
| `dto/request/PanelCreateRequest.java` | 创建分镜请求 |
| `dto/request/PanelUpdateRequest.java` | 更新分镜请求 |
| `dto/request/PanelReviseRequest.java` | 分镜修改请求 |
| `dto/request/ComicReviseRequest.java` | 四宫格修改请求 |
| `dto/response/EpisodeListItemResponse.java` | 剧集列表项 |
| `dto/response/PanelListItemResponse.java` | 分镜列表项 |
| `dto/response/ComicStatusResponse.java` | 四宫格状态响应 |
| `dto/response/VideoStatusResponse.java` | 视频状态响应 |
| `dto/response/PanelProductionStatusResponse.java` | 重写：适配新管线（背景/四宫格/视频） |

### 删除文件
| 文件 | 原因 |
|------|------|
| `controller/StoryController.java` | 职责拆分完毕 |
| `service/production/GridSplitService.java` | 九宫格切分流程废弃 |

---

## Task 1: Episode DTOs

**Files:**
- Create: `dto/request/EpisodeCreateRequest.java`
- Create: `dto/request/EpisodeUpdateRequest.java`
- Create: `dto/response/EpisodeListItemResponse.java`

- [ ] **Step 1: 创建 EpisodeCreateRequest**

```java
package com.comic.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class EpisodeCreateRequest {
    private String status;
    private Map<String, Object> episodeInfo;
}
```

- [ ] **Step 2: 创建 EpisodeUpdateRequest**

```java
package com.comic.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class EpisodeUpdateRequest {
    private String status;
    private Map<String, Object> episodeInfo;
}
```

- [ ] **Step 3: 创建 EpisodeListItemResponse**

```java
package com.comic.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class EpisodeListItemResponse {
    private Long id;
    private String projectId;
    private String status;
    private Map<String, Object> episodeInfo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: Commit**

```bash
git add dto/request/EpisodeCreateRequest.java dto/request/EpisodeUpdateRequest.java dto/response/EpisodeListItemResponse.java
git commit -m "feat: add Episode CRUD DTOs"
```

---

## Task 2: EpisodeRepository 增强

**Files:**
- Modify: `repository/EpisodeRepository.java`

- [ ] **Step 1: 新增 `findByProjectIdAndId` 和 `findPageByProjectId`**

在 `EpisodeRepository.java` 中新增两个 default 方法：

```java
default Episode findByProjectIdAndId(String projectId, Long episodeId) {
    return selectOne(new LambdaQueryWrapper<Episode>()
        .eq(Episode::getProjectId, projectId)
        .eq(Episode::getId, episodeId));
}

default IPage<Episode> findPageByProjectId(String projectId, String name, IPage<Episode> page) {
    LambdaQueryWrapper<Episode> wrapper = new LambdaQueryWrapper<Episode>()
        .eq(Episode::getProjectId, projectId);
    if (name != null && !name.isEmpty()) {
        wrapper.apply("JSON_EXTRACT(episode_info, '$.title') LIKE {0}", "%" + name + "%");
    }
    wrapper.orderByAsc(Episode::getId);
    return selectPage(page, wrapper);
}
```

需要新增 import：
```java
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
```

- [ ] **Step 2: Commit**

```bash
git add repository/EpisodeRepository.java
git commit -m "feat: add findByProjectIdAndId and findPageByProjectId to EpisodeRepository"
```

---

## Task 3: EpisodeService

**Files:**
- Create: `service/episode/EpisodeService.java`

- [ ] **Step 1: 创建 EpisodeService**

```java
package com.comic.service.episode;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comic.common.BusinessException;
import com.comic.dto.request.EpisodeCreateRequest;
import com.comic.dto.request.EpisodeUpdateRequest;
import com.comic.dto.response.EpisodeListItemResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EpisodeService {

    private final EpisodeRepository episodeRepository;

    public PaginatedResponse<EpisodeListItemResponse> getEpisodesPage(String projectId, String name, int page, int size) {
        IPage<Episode> episodePage = episodeRepository.findPageByProjectId(
                projectId, name, new Page<>(page, size));
        List<EpisodeListItemResponse> items = episodePage.getRecords().stream()
                .map(this::toListItemResponse)
                .collect(Collectors.toList());
        return PaginatedResponse.of(items, episodePage.getTotal(), (int) episodePage.getCurrent(), (int) episodePage.getSize());
    }

    public EpisodeListItemResponse getEpisode(String projectId, Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        return toListItemResponse(episode);
    }

    @Transactional
    public EpisodeListItemResponse createEpisode(String projectId, EpisodeCreateRequest request) {
        Episode episode = new Episode();
        episode.setProjectId(projectId);
        episode.setStatus(request.getStatus() != null ? request.getStatus() : "DRAFT");
        episode.setEpisodeInfo(request.getEpisodeInfo());
        episodeRepository.insert(episode);
        return toListItemResponse(episode);
    }

    @Transactional
    public void updateEpisode(String projectId, Long episodeId, EpisodeUpdateRequest request) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        if (request.getStatus() != null) {
            episode.setStatus(request.getStatus());
        }
        if (request.getEpisodeInfo() != null) {
            // 部分更新：合并到现有 episodeInfo
            Map<String, Object> existingInfo = episode.getEpisodeInfo();
            if (existingInfo == null) {
                episode.setEpisodeInfo(request.getEpisodeInfo());
            } else {
                existingInfo.putAll(request.getEpisodeInfo());
                episode.setEpisodeInfo(existingInfo);
            }
        }
        episodeRepository.updateById(episode);
    }

    @Transactional
    public void deleteEpisode(String projectId, Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        episodeRepository.deleteById(episodeId);
    }

    private EpisodeListItemResponse toListItemResponse(Episode episode) {
        EpisodeListItemResponse response = new EpisodeListItemResponse();
        response.setId(episode.getId());
        response.setProjectId(episode.getProjectId());
        response.setStatus(episode.getStatus());
        response.setEpisodeInfo(episode.getEpisodeInfo());
        response.setCreatedAt(episode.getCreatedAt());
        response.setUpdatedAt(episode.getUpdatedAt());
        return response;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add service/episode/EpisodeService.java
git commit -m "feat: create EpisodeService with CRUD and pagination"
```

**设计决策说明：**
- `deleteEpisode` 执行的是物理删除（`deleteById`），Spec 明确说明逻辑删除不在本次范围
- EpisodeController 的 `@Operation(summary = "删除剧集")` 不标注"逻辑删除"，避免误导

---

## Task 4: EpisodeController 重写

**Files:**
- Modify: `controller/EpisodeController.java`（完全重写）

- [ ] **Step 1: 重写 EpisodeController**

将 `controller/EpisodeController.java` 完全替换为以下内容（参照 CharacterController 风格）：

```java
package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.EpisodeCreateRequest;
import com.comic.dto.request.EpisodeUpdateRequest;
import com.comic.dto.response.EpisodeListItemResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.service.episode.EpisodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/episodes")
@RequiredArgsConstructor
@Tag(name = "剧集管理")
@SecurityRequirement(name = "bearerAuth")
public class EpisodeController {

    private final EpisodeService episodeService;

    @GetMapping
    @Operation(summary = "剧集列表（分页）")
    public Result<PaginatedResponse<EpisodeListItemResponse>> getEpisodes(
            @PathVariable String projectId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "名称模糊搜索") @RequestParam(required = false) String name) {
        return Result.ok(episodeService.getEpisodesPage(projectId, name, page, size));
    }

    @GetMapping("/{episodeId}")
    @Operation(summary = "剧集详情")
    public Result<EpisodeListItemResponse> getEpisode(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        return Result.ok(episodeService.getEpisode(projectId, episodeId));
    }

    @PostMapping
    @Operation(summary = "创建剧集")
    public Result<EpisodeListItemResponse> createEpisode(
            @PathVariable String projectId,
            @RequestBody EpisodeCreateRequest request) {
        return Result.ok(episodeService.createEpisode(projectId, request));
    }

    @PutMapping("/{episodeId}")
    @Operation(summary = "更新剧集")
    public Result<Void> updateEpisode(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @RequestBody EpisodeUpdateRequest request) {
        episodeService.updateEpisode(projectId, episodeId, request);
        return Result.ok();
    }

    @DeleteMapping("/{episodeId}")
    @Operation(summary = "删除剧集")
    public Result<Void> deleteEpisode(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        episodeService.deleteEpisode(projectId, episodeId);
        return Result.ok();
    }
}
```

**注意：** 旧 EpisodeController 的所有生产接口（produce, fusion-image, split-grid-page 等）全部移除。这些接口的功能将由 PanelController 接管。

- [ ] **Step 2: 检查是否有其他文件引用旧的 EpisodeController 路径**

```bash
grep -r "api/episodes" --include="*.java" backend/com/comic/src/
```

如果有前端或配置文件引用旧路径，记录下来但不修改（本次只改后端）。

- [ ] **Step 3: Commit**

```bash
git add controller/EpisodeController.java
git commit -m "feat: rewrite EpisodeController — pure CRUD, nested under project"
```

---

## Task 5: Panel DTOs

**Files:**
- Create: `dto/request/PanelCreateRequest.java`
- Create: `dto/request/PanelUpdateRequest.java`
- Create: `dto/request/PanelReviseRequest.java`
- Create: `dto/request/ComicReviseRequest.java`
- Create: `dto/response/PanelListItemResponse.java`
- Create: `dto/response/ComicStatusResponse.java`
- Create: `dto/response/VideoStatusResponse.java`

- [ ] **Step 1: 创建 PanelCreateRequest**

```java
package com.comic.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class PanelCreateRequest {
    private String status;
    private Map<String, Object> panelInfo;
}
```

- [ ] **Step 2: 创建 PanelUpdateRequest**

```java
package com.comic.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class PanelUpdateRequest {
    private String status;
    private Map<String, Object> panelInfo;
}
```

- [ ] **Step 3: 创建 PanelReviseRequest**

```java
package com.comic.dto.request;

import lombok.Data;

@Data
public class PanelReviseRequest {
    private String feedback;
}
```

- [ ] **Step 4: 创建 ComicReviseRequest**

```java
package com.comic.dto.request;

import lombok.Data;

@Data
public class ComicReviseRequest {
    private String feedback;
}
```

- [ ] **Step 5: 创建 PanelListItemResponse**

```java
package com.comic.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class PanelListItemResponse {
    private Long id;
    private Long episodeId;
    private String status;
    private Map<String, Object> panelInfo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 6: 创建 ComicStatusResponse**

```java
package com.comic.dto.response;

import lombok.Data;

@Data
public class ComicStatusResponse {
    private Long panelId;
    private String status;        // pending, generating, approved, needs_revision, failed
    private String comicUrl;
    private String backgroundUrl;
    private String errorMessage;
}
```

- [ ] **Step 7: 创建 VideoStatusResponse**

```java
package com.comic.dto.response;

import lombok.Data;

@Data
public class VideoStatusResponse {
    private Long panelId;
    private String status;        // pending, generating, completed, failed
    private String videoUrl;
    private String taskId;
    private String errorMessage;
    private Integer duration;
}
```

- [ ] **Step 8: Commit**

```bash
git add dto/request/PanelCreateRequest.java dto/request/PanelUpdateRequest.java dto/request/PanelReviseRequest.java dto/request/ComicReviseRequest.java dto/response/PanelListItemResponse.java dto/response/ComicStatusResponse.java dto/response/VideoStatusResponse.java
git commit -m "feat: add Panel CRUD and production DTOs"
```

---

## Task 6: PanelRepository 增强 + PanelService

**Files:**
- Modify: `repository/PanelRepository.java`
- Create: `service/panel/PanelService.java`

- [ ] **Step 1: 在 PanelRepository 新增方法**

```java
default Panel findByEpisodeIdAndId(Long episodeId, Long panelId) {
    return selectOne(new LambdaQueryWrapper<Panel>()
        .eq(Panel::getEpisodeId, episodeId)
        .eq(Panel::getId, panelId));
}
```

- [ ] **Step 2: 创建 PanelService**

**关键设计：** 所有方法接收 `projectId` 参数，在 Service 层校验 episode → project 归属关系。这是 Spec 要求的"Service 层校验归属关系"。

```java
package com.comic.service.panel;

import com.comic.common.BusinessException;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.PanelListItemResponse;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PanelService {

    private final PanelRepository panelRepository;
    private final EpisodeRepository episodeRepository;

    /**
     * 校验 episode 归属于 project，返回 Episode 或抛异常
     */
    private Episode validateEpisodeOwnership(String projectId, Long episodeId) {
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            throw new BusinessException("剧集不存在");
        }
        return episode;
    }

    public List<PanelListItemResponse> getPanels(String projectId, Long episodeId) {
        validateEpisodeOwnership(projectId, episodeId);
        return panelRepository.findByEpisodeId(episodeId).stream()
                .map(this::toListItemResponse)
                .collect(Collectors.toList());
    }

    public PanelListItemResponse getPanel(String projectId, Long episodeId, Long panelId) {
        validateEpisodeOwnership(projectId, episodeId);
        Panel panel = panelRepository.findByEpisodeIdAndId(episodeId, panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }
        return toListItemResponse(panel);
    }

    @Transactional
    public PanelListItemResponse createPanel(String projectId, Long episodeId, PanelCreateRequest request) {
        validateEpisodeOwnership(projectId, episodeId);
        Panel panel = new Panel();
        panel.setEpisodeId(episodeId);
        panel.setStatus(request.getStatus() != null ? request.getStatus() : "DRAFT");
        panel.setPanelInfo(request.getPanelInfo());
        panelRepository.insert(panel);
        return toListItemResponse(panel);
    }

    @Transactional
    public void updatePanel(String projectId, Long episodeId, Long panelId, PanelUpdateRequest request) {
        validateEpisodeOwnership(projectId, episodeId);
        Panel panel = panelRepository.findByEpisodeIdAndId(episodeId, panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }
        if (request.getStatus() != null) {
            panel.setStatus(request.getStatus());
        }
        if (request.getPanelInfo() != null) {
            Map<String, Object> existingInfo = panel.getPanelInfo();
            if (existingInfo == null) {
                panel.setPanelInfo(request.getPanelInfo());
            } else {
                existingInfo.putAll(request.getPanelInfo());
                panel.setPanelInfo(existingInfo);
            }
        }
        panelRepository.updateById(panel);
    }

    @Transactional
    public void deletePanel(String projectId, Long episodeId, Long panelId) {
        validateEpisodeOwnership(projectId, episodeId);
        Panel panel = panelRepository.findByEpisodeIdAndId(episodeId, panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }
        panelRepository.deleteById(panelId);
    }

    private PanelListItemResponse toListItemResponse(Panel panel) {
        PanelListItemResponse response = new PanelListItemResponse();
        response.setId(panel.getId());
        response.setEpisodeId(panel.getEpisodeId());
        response.setStatus(panel.getStatus());
        response.setPanelInfo(panel.getPanelInfo());
        response.setCreatedAt(panel.getCreatedAt());
        response.setUpdatedAt(panel.getUpdatedAt());
        return response;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add repository/PanelRepository.java service/panel/PanelService.java
git commit -m "feat: add findByEpisodeIdAndId to PanelRepository, create PanelService"
```

---

## Task 7: 重写 PanelProductionStatusResponse

**Files:**
- Modify: `dto/response/PanelProductionStatusResponse.java`

- [ ] **Step 1: 重写 PanelProductionStatusResponse**

旧版字段：background, fusion, transition, video, tailFrame
新版字段：background, comic, video（适配新的 3 步管线）

```java
package com.comic.dto.response;

import lombok.Data;

/**
 * 单分镜完整生产状态响应
 * 管线：背景图 → 四宫格漫画（审核点）→ AI 视频
 */
@Data
public class PanelProductionStatusResponse {
    private Long panelId;
    private String overallStatus;   // pending, in_progress, completed, failed
    private String currentStage;    // background, comic, video

    // 背景图
    private String backgroundStatus;    // pending, generating, completed, failed
    private String backgroundUrl;

    // 四宫格漫画
    private String comicStatus;         // pending, generating, approved, needs_revision, failed
    private String comicUrl;

    // AI 视频
    private String videoStatus;         // pending, generating, completed, failed
    private String videoUrl;
    private Integer videoDuration;

    // 错误信息
    private String errorMessage;
}
```

- [ ] **Step 2: Commit**

```bash
git add dto/response/PanelProductionStatusResponse.java
git commit -m "feat: rewrite PanelProductionStatusResponse for new pipeline"
```

---

## Task 8: PanelController — CRUD + 分镜生成接口

**Files:**
- Create: `controller/PanelController.java`

- [ ] **Step 1: 创建 PanelController（CRUD + 分镜生成，共 10 个接口）**

```java
package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.request.PanelCreateRequest;
import com.comic.dto.request.PanelReviseRequest;
import com.comic.dto.request.PanelUpdateRequest;
import com.comic.dto.response.PanelListItemResponse;
import com.comic.entity.Episode;
import com.comic.repository.EpisodeRepository;
import com.comic.service.job.JobQueueService;
import com.comic.service.panel.PanelService;
import com.comic.service.story.StoryboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/episodes/{episodeId}/panels")
@RequiredArgsConstructor
@Tag(name = "分镜管理与生产")
@SecurityRequirement(name = "bearerAuth")
public class PanelController {

    private final PanelService panelService;
    private final StoryboardService storyboardService;
    private final JobQueueService jobQueueService;
    private final EpisodeRepository episodeRepository;

    // ================= 分镜 CRUD =================

    @GetMapping
    @Operation(summary = "分镜列表")
    public Result<List<PanelListItemResponse>> getPanels(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        return Result.ok(panelService.getPanels(projectId, episodeId));
    }

    @GetMapping("/{panelId}")
    @Operation(summary = "分镜详情")
    public Result<PanelListItemResponse> getPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        return Result.ok(panelService.getPanel(projectId, episodeId, panelId));
    }

    @PostMapping
    @Operation(summary = "创建分镜")
    public Result<PanelListItemResponse> createPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @RequestBody PanelCreateRequest request) {
        return Result.ok(panelService.createPanel(projectId, episodeId, request));
    }

    @PutMapping("/{panelId}")
    @Operation(summary = "更新分镜")
    public Result<Void> updatePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody PanelUpdateRequest request) {
        panelService.updatePanel(projectId, episodeId, panelId, request);
        return Result.ok();
    }

    @DeleteMapping("/{panelId}")
    @Operation(summary = "删除分镜")
    public Result<Void> deletePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        panelService.deletePanel(projectId, episodeId, panelId);
        return Result.ok();
    }

    // ================= 分镜生成流程（从 StoryController 迁入） =================

    @PostMapping("/generate")
    @Operation(summary = "AI 生成分镜", description = "LLM 生成 + 镜头增强")
    public Result<Map<String, String>> generatePanels(
            @PathVariable String projectId,
            @PathVariable Long episodeId) {
        // 校验 episode 归属于 project
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            return Result.fail(404, "剧集不存在");
        }
        String jobId = jobQueueService.submitStoryboardJob(episodeId);
        Map<String, String> result = new HashMap<>();
        result.put("jobId", jobId);
        return Result.ok(result);
    }

    @GetMapping("/generate/{jobId}/status")
    @Operation(summary = "分镜生成任务状态")
    public Result<Map<String, Object>> getGenerateStatus(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable String jobId) {
        Map<String, Object> status = new HashMap<>();
        status.put("jobId", jobId);
        // 从 Episode 的状态推导 job 状态（StoryboardService 会更新 episode.status）
        Episode episode = episodeRepository.findByProjectIdAndId(projectId, episodeId);
        if (episode == null) {
            return Result.fail(404, "剧集不存在");
        }
        String epStatus = episode.getStatus();
        if ("STORYBOARD_DONE".equals(epStatus) || "STORYBOARD_CONFIRMED".equals(epStatus)) {
            status.put("status", "completed");
        } else if ("STORYBOARD_FAILED".equals(epStatus)) {
            status.put("status", "failed");
            Map<String, Object> info = episode.getEpisodeInfo();
            if (info != null && info.get("errorMsg") != null) {
                status.put("errorMessage", info.get("errorMsg").toString());
            }
        } else if ("STORYBOARD_GENERATING".equals(epStatus)) {
            status.put("status", "processing");
        } else {
            status.put("status", "pending");
        }
        return Result.ok(status);
    }

    @PostMapping("/{panelId}/confirm")
    @Operation(summary = "确认分镜内容")
    public Result<Void> confirmPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        // 注意：StoryboardService 的 confirm 是 episode 级别操作，不是 panel 级别
        storyboardService.confirmEpisodeStoryboard(episodeId);
        return Result.ok();
    }

    @PostMapping("/{panelId}/revise")
    @Operation(summary = "修改分镜")
    public Result<Void> revisePanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId,
            @RequestBody PanelReviseRequest request) {
        storyboardService.reviseEpisodeStoryboard(episodeId, request.getFeedback());
        return Result.ok();
    }

    @PostMapping("/{panelId}/retry")
    @Operation(summary = "重试失败的生成")
    public Result<Void> retryPanel(
            @PathVariable String projectId,
            @PathVariable Long episodeId,
            @PathVariable Long panelId) {
        storyboardService.retryFailedStoryboard(episodeId);
        return Result.ok();
    }

    // ================= 生产状态查询（Task 9 补充） =================
    // 背景图生成（Task 10 补充） =================
    // 四宫格漫画（Task 11 补充） =================
    // AI 视频生成（Task 12 补充） =================
}
```

- [ ] **Step 2: Commit**

```bash
git add controller/PanelController.java
git commit -m "feat: create PanelController with CRUD and storyboard generation endpoints"
```

---

## Task 9: PanelController — 生产状态查询

**Files:**
- Modify: `controller/PanelController.java`
- Modify: `service/production/PanelProductionService.java`（新增生产状态查询方法）

- [ ] **Step 1: 在 PanelProductionService 新增 getProductionStatus 和 getBatchProductionStatus**

在 `PanelProductionService.java` 中新增方法（注意：旧代码的 `getPanelProductionStatus` 参数是 `episodeId + panelIndex`，新版改为基于 panel 实体）：

```java
/**
 * 获取单 Panel 生产状态（新版：基于 panelId）
 */
public PanelProductionStatusResponse getProductionStatus(Long panelId) {
    Panel panel = panelRepository.selectById(panelId);
    if (panel == null) {
        throw new BusinessException("分镜不存在");
    }

    PanelProductionStatusResponse response = new PanelProductionStatusResponse();
    response.setPanelId(panelId);

    Map<String, Object> info = panel.getPanelInfo();
    if (info == null) {
        response.setOverallStatus("pending");
        response.setCurrentStage("background");
        return response;
    }

    // 背景图状态
    String bgUrl = getStringFromInfo(info, "backgroundUrl");
    response.setBackgroundUrl(bgUrl);
    response.setBackgroundStatus(bgUrl != null ? "completed" : "pending");

    // 四宫格状态
    String comicUrl = getStringFromInfo(info, "comicUrl");
    String comicStatus = getStringFromInfo(info, "comicStatus");
    response.setComicUrl(comicUrl);
    response.setComicStatus(comicStatus != null ? comicStatus : (comicUrl != null ? "approved" : "pending"));

    // 视频状态
    String videoUrl = getStringFromInfo(info, "videoUrl");
    String videoStatus = getStringFromInfo(info, "videoStatus");
    response.setVideoUrl(videoUrl);
    response.setVideoStatus(videoStatus != null ? videoStatus : (videoUrl != null ? "completed" : "pending"));

    // 整体状态
    response.setOverallStatus(determineOverallStatus(response));
    response.setCurrentStage(determineCurrentStage(response));

    return response;
}

/**
 * 批量获取 Episode 下所有 Panel 的生产状态
 */
public List<PanelProductionStatusResponse> getBatchProductionStatus(Long episodeId) {
    List<Panel> panels = panelRepository.findByEpisodeId(episodeId);
    return panels.stream()
            .map(panel -> getProductionStatus(panel.getId()))
            .collect(Collectors.toList());
}

private String getStringFromInfo(Map<String, Object> info, String key) {
    Object v = info.get(key);
    return v != null ? v.toString() : null;
}

private String determineOverallStatus(PanelProductionStatusResponse r) {
    if ("completed".equals(r.getVideoStatus())) return "completed";
    if ("failed".equals(r.getVideoStatus()) || "failed".equals(r.getComicStatus()) || "failed".equals(r.getBackgroundStatus())) return "failed";
    if ("generating".equals(r.getVideoStatus()) || "generating".equals(r.getComicStatus()) || "generating".equals(r.getBackgroundStatus())) return "in_progress";
    if (r.getBackgroundUrl() != null || r.getComicUrl() != null) return "in_progress";
    return "pending";
}

private String determineCurrentStage(PanelProductionStatusResponse r) {
    if ("completed".equals(r.getVideoStatus())) return "video";
    if ("generating".equals(r.getVideoStatus())) return "video";
    if ("approved".equals(r.getComicStatus())) return "video";
    if ("generating".equals(r.getComicStatus())) return "comic";
    if ("pending".equals(r.getComicStatus()) && r.getBackgroundUrl() != null) return "comic";
    if ("generating".equals(r.getBackgroundStatus())) return "background";
    return "background";
}
```

需要在 PanelProductionService 中注入 `PanelRepository panelRepository`（如果还没有的话）。

- [ ] **Step 2: 在 PanelController 添加生产状态接口**

在 `PanelController.java` 的注释占位处添加：

```java
// ================= 生产状态查询 =================

@GetMapping("/{panelId}/production-status")
@Operation(summary = "单 Panel 完整生产状态")
public Result<PanelProductionStatusResponse> getProductionStatus(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    return Result.ok(panelProductionService.getProductionStatus(panelId));
}

@GetMapping("/production-statuses")
@Operation(summary = "批量获取所有 Panel 生产状态")
public Result<List<PanelProductionStatusResponse>> getBatchProductionStatuses(
        @PathVariable String projectId,
        @PathVariable Long episodeId) {
    return Result.ok(panelProductionService.getBatchProductionStatus(episodeId));
}
```

在 PanelController 中注入：
```java
private final PanelProductionService panelProductionService;
```

- [ ] **Step 3: Commit**

```bash
git add controller/PanelController.java service/production/PanelProductionService.java
git commit -m "feat: add production status query endpoints to PanelController"
```

---

## Task 10: PanelController — 背景图生成

**Files:**
- Modify: `controller/PanelController.java`
- Modify: `service/production/PanelProductionService.java`

- [ ] **Step 1: 更新 PanelBackgroundResponse**

在 `dto/response/PanelBackgroundResponse.java` 中新增 `panelId` 字段（保留旧 `panelIndex` 字段以兼容其他可能引用它的代码）：

```java
private Long panelId;  // 新增：新模型使用 panelId
```

- [ ] **Step 2: 在 PanelProductionService 新增背景图方法（新版基于 panelId）**

```java
/**
 * 获取背景图状态
 */
public PanelBackgroundResponse getBackgroundStatus(Long panelId) {
    Panel panel = panelRepository.selectById(panelId);
    if (panel == null) {
        throw new BusinessException("分镜不存在");
    }

    PanelBackgroundResponse response = new PanelBackgroundResponse();
    response.setPanelIndex(panel.getId().intValue()); // 兼容旧字段

    Map<String, Object> info = panel.getPanelInfo();
    String bgUrl = info != null ? getStringFromInfo(info, "backgroundUrl") : null;
    if (bgUrl != null) {
        response.setBackgroundUrl(bgUrl);
        response.setStatus("completed");
    } else {
        response.setStatus("pending");
    }
    return response;
}

/**
 * 生成背景图（异步）
 */
public void generateBackground(Long panelId) {
    self().doGenerateBackground(panelId);
}

@Async
public void doGenerateBackground(Long panelId) {
    try {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }

        String prompt = extractSceneDescription(panel);
        String imageUrl = imageGenerationService.generate(prompt, 1280, 720, "anime");

        Map<String, Object> info = panel.getPanelInfo();
        if (info == null) {
            info = new HashMap<>();
        }
        info.put("backgroundUrl", imageUrl);
        info.put("backgroundStatus", "completed");
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);

        log.info("背景图生成完成: panelId={}", panelId);
    } catch (Exception e) {
        log.error("背景图生成失败: panelId={}", panelId, e);
        updatePanelProductionState(panelId, "backgroundStatus", "failed", e.getMessage());
        throw new BusinessException("背景图生成失败: " + e.getMessage());
    }
}

private String extractSceneDescription(Panel panel) {
    Map<String, Object> info = panel.getPanelInfo();
    if (info == null) return "";
    // 优先使用 sceneDescription，其次 background.scene_desc
    String desc = getStringFromInfo(info, "sceneDescription");
    if (desc != null && !desc.isEmpty()) return desc;
    desc = getStringFromInfo(info, "image_prompt_hint");
    return desc != null ? desc : "";
}

private void updatePanelProductionState(Long panelId, String stateKey, String stateValue, String errorMsg) {
    Panel panel = panelRepository.selectById(panelId);
    if (panel == null) return;
    Map<String, Object> info = panel.getPanelInfo();
    if (info == null) info = new HashMap<>();
    info.put(stateKey, stateValue);
    if (errorMsg != null) info.put("errorMessage", errorMsg);
    panel.setPanelInfo(info);
    panelRepository.updateById(panel);
}
```

需要新增 import：
```java
import com.comic.entity.Panel;
import com.comic.repository.PanelRepository;
import java.util.HashMap;
```

- [ ] **Step 2: 在 PanelController 添加背景图接口**

```java
// ================= 背景图生成 =================

@GetMapping("/{panelId}/background")
@Operation(summary = "获取背景图状态")
public Result<PanelBackgroundResponse> getBackgroundStatus(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    return Result.ok(panelProductionService.getBackgroundStatus(panelId));
}

@PostMapping("/{panelId}/background")
@Operation(summary = "生成背景图（自动匹配角色）")
public Result<Void> generateBackground(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    panelProductionService.generateBackground(panelId);
    return Result.ok();
}

@PostMapping("/{panelId}/background/regenerate")
@Operation(summary = "重新生成背景图")
public Result<Void> regenerateBackground(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    panelProductionService.generateBackground(panelId);
    return Result.ok();
}
```

- [ ] **Step 3: Commit**

```bash
git add controller/PanelController.java service/production/PanelProductionService.java
git commit -m "feat: add background generation endpoints to PanelController"
```

---

## Task 11: ComicGenerationService + 四宫格接口

**Files:**
- Create: `service/production/ComicGenerationService.java`
- Modify: `controller/PanelController.java`

- [ ] **Step 1: 创建 ComicGenerationService**

```java
package com.comic.service.production;

import com.comic.ai.image.ImageGenerationService;
import com.comic.common.BusinessException;
import com.comic.dto.response.ComicStatusResponse;
import com.comic.entity.Panel;
import com.comic.repository.PanelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 四宫格漫画 AI 融合生成服务
 * 背景 + 角色 → 调用图像大模型 → 2×2 四宫格
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComicGenerationService {

    private final PanelRepository panelRepository;
    private final ImageGenerationService imageGenerationService;
    private final ApplicationContext applicationContext;

    private ComicGenerationService self() {
        return applicationContext.getBean(ComicGenerationService.class);
    }

    /**
     * 获取四宫格状态
     */
    public ComicStatusResponse getComicStatus(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }

        ComicStatusResponse response = new ComicStatusResponse();
        response.setPanelId(panelId);

        Map<String, Object> info = panel.getPanelInfo();
        String comicUrl = info != null ? getStr(info, "comicUrl") : null;
        String comicStatus = info != null ? getStr(info, "comicStatus") : null;
        String bgUrl = info != null ? getStr(info, "backgroundUrl") : null;
        String errorMsg = info != null ? getStr(info, "errorMessage") : null;

        response.setBackgroundUrl(bgUrl);
        response.setComicUrl(comicUrl);
        response.setStatus(comicStatus != null ? comicStatus : (comicUrl != null ? "approved" : "pending"));
        response.setErrorMessage(errorMsg);
        return response;
    }

    /**
     * 生成四宫格漫画（异步）
     * 前置条件：背景图已生成
     */
    public void generateComic(Long panelId) {
        self().doGenerateComic(panelId);
    }

    @Async
    public void doGenerateComic(Long panelId) {
        try {
            Panel panel = panelRepository.selectById(panelId);
            if (panel == null) {
                throw new BusinessException("分镜不存在");
            }

            Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();

            String bgUrl = getStr(info, "backgroundUrl");
            if (bgUrl == null || bgUrl.isEmpty()) {
                throw new BusinessException("背景图不存在，请先生成背景图");
            }

            // 更新状态为生成中
            info.put("comicStatus", "generating");
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);

            // 构建 prompt：生成四宫格融合图
            String sceneDesc = getStr(info, "sceneDescription");
            String prompt = buildComicPrompt(sceneDesc, panelId);

            // 调用图像生成 API（使用背景图作为参考）
            String comicUrl = imageGenerationService.generateWithReference(prompt, bgUrl, 1280, 720);

            // 更新状态
            info.put("comicUrl", comicUrl);
            info.put("comicStatus", "pending_review");
            info.put("errorMessage", null);
            panel.setPanelInfo(info);
            panelRepository.updateById(panel);

            log.info("四宫格漫画生成完成: panelId={}", panelId);
        } catch (Exception e) {
            log.error("四宫格漫画生成失败: panelId={}", panelId, e);
            Panel panel = panelRepository.selectById(panelId);
            if (panel != null) {
                Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
                info.put("comicStatus", "failed");
                info.put("errorMessage", e.getMessage());
                panel.setPanelInfo(info);
                panelRepository.updateById(panel);
            }
            throw new BusinessException("四宫格漫画生成失败: " + e.getMessage());
        }
    }

    /**
     * 审核通过四宫格
     */
    public void approveComic(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }

        Map<String, Object> info = panel.getPanelInfo();
        String comicUrl = info != null ? getStr(info, "comicUrl") : null;
        if (comicUrl == null) {
            throw new BusinessException("四宫格漫画不存在，请先生成");
        }

        info.put("comicStatus", "approved");
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
    }

    /**
     * 退回重生成四宫格（带修改建议）
     */
    public void reviseComic(Long panelId, String feedback) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }

        Map<String, Object> info = panel.getPanelInfo() != null ? panel.getPanelInfo() : new HashMap<>();
        info.put("comicStatus", "needs_revision");
        info.put("revisionFeedback", feedback);
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);

        // 异步重新生成
        self().doGenerateComic(panelId);
    }

    private String buildComicPrompt(String sceneDesc, Long panelId) {
        StringBuilder prompt = new StringBuilder();
        if (sceneDesc != null && !sceneDesc.isEmpty()) {
            prompt.append(sceneDesc);
        }
        prompt.append(". Create a 2x2 comic grid layout with 4 sequential scenes, ");
        prompt.append("with sequence numbers (1,2,3,4) marked on each cell. ");
        prompt.append("Anime style, high quality.");
        return prompt.toString();
    }

    private String getStr(Map<String, Object> info, String key) {
        Object v = info.get(key);
        return v != null ? v.toString() : null;
    }
}
```

- [ ] **Step 2: 在 PanelController 添加四宫格接口**

注入 ComicGenerationService：
```java
private final ComicGenerationService comicGenerationService;
```

添加接口：
```java
// ================= 四宫格漫画（AI 融合，审核点） =================

@GetMapping("/{panelId}/comic")
@Operation(summary = "获取四宫格状态")
public Result<ComicStatusResponse> getComicStatus(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    return Result.ok(comicGenerationService.getComicStatus(panelId));
}

@PostMapping("/{panelId}/comic")
@Operation(summary = "生成四宫格漫画")
public Result<Void> generateComic(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    comicGenerationService.generateComic(panelId);
    return Result.ok();
}

@PostMapping("/{panelId}/comic/approve")
@Operation(summary = "审核通过四宫格")
public Result<Void> approveComic(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    comicGenerationService.approveComic(panelId);
    return Result.ok();
}

@PostMapping("/{panelId}/comic/revise")
@Operation(summary = "退回重生成四宫格")
public Result<Void> reviseComic(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId,
        @RequestBody ComicReviseRequest request) {
    comicGenerationService.reviseComic(panelId, request.getFeedback());
    return Result.ok();
}
```

需要新增 import：
```java
import com.comic.dto.request.ComicReviseRequest;
import com.comic.dto.response.ComicStatusResponse;
```

- [ ] **Step 3: Commit**

```bash
git add service/production/ComicGenerationService.java controller/PanelController.java
git commit -m "feat: add ComicGenerationService and comic endpoints to PanelController"
```

---

## Task 12: PanelController — AI 视频生成

**Files:**
- Modify: `controller/PanelController.java`
- Modify: `service/production/PanelProductionService.java`

- [ ] **Step 1: 在 PanelProductionService 新增视频生成方法（新版基于 panelId）**

```java
/**
 * 获取视频状态
 */
public VideoStatusResponse getVideoStatus(Long panelId) {
    Panel panel = panelRepository.selectById(panelId);
    if (panel == null) {
        throw new BusinessException("分镜不存在");
    }

    VideoStatusResponse response = new VideoStatusResponse();
    response.setPanelId(panelId);

    Map<String, Object> info = panel.getPanelInfo();
    String videoUrl = info != null ? getStringFromInfo(info, "videoUrl") : null;
    String videoStatus = info != null ? getStringFromInfo(info, "videoStatus") : null;
    String taskId = info != null ? getStringFromInfo(info, "videoTaskId") : null;
    String errorMsg = info != null ? getStringFromInfo(info, "errorMessage") : null;

    response.setVideoUrl(videoUrl);
    response.setStatus(videoStatus != null ? videoStatus : (videoUrl != null ? "completed" : "pending"));
    response.setTaskId(taskId);
    response.setErrorMessage(errorMsg);
    return response;
}

/**
 * 生成视频（异步）
 * 前置条件：四宫格漫画已审核通过
 */
public void generateVideo(Long panelId) {
    self().doGenerateVideo(panelId);
}

@Async
public void doGenerateVideo(Long panelId) {
    try {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) {
            throw new BusinessException("分镜不存在");
        }

        Map<String, Object> info = panel.getPanelInfo();
        String comicUrl = getStringFromInfo(info, "comicUrl");
        String comicStatus = getStringFromInfo(info, "comicStatus");

        if (!"approved".equals(comicStatus) || comicUrl == null) {
            throw new BusinessException("四宫格漫画未审核通过，请先审核");
        }

        // 更新状态
        info.put("videoStatus", "generating");
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);

        // 生成视频
        String prompt = extractSceneDescription(panel);
        String taskId = videoGenerationService.generateAsync(prompt, 5, "16:9", comicUrl);

        info.put("videoTaskId", taskId);
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);

        // 异步轮询视频状态
        self().pollNewVideoTask(panelId, taskId);

        log.info("视频生成已提交: panelId={}, taskId={}", panelId, taskId);
    } catch (Exception e) {
        log.error("视频生成失败: panelId={}", panelId, e);
        updatePanelProductionState(panelId, "videoStatus", "failed", e.getMessage());
        throw new BusinessException("视频生成失败: " + e.getMessage());
    }
}

@Async
public void pollNewVideoTask(Long panelId, String taskId) {
    try {
        int maxAttempts = 120;
        for (int i = 0; i < maxAttempts; i++) {
            VideoGenerationService.TaskStatus status = videoGenerationService.getTaskStatus(taskId);
            if (status == null) {
                Thread.sleep(5000);
                continue;
            }

            switch (status.getStatus()) {
                case "completed":
                    String videoUrl = status.getVideoUrl();
                    if (videoUrl == null) videoUrl = videoGenerationService.downloadVideo(status.getTaskId());

                    Panel panel = panelRepository.selectById(panelId);
                    if (panel != null) {
                        Map<String, Object> info = panel.getPanelInfo();
                        info.put("videoUrl", videoUrl);
                        info.put("videoStatus", "completed");
                        info.put("errorMessage", null);
                        panel.setPanelInfo(info);
                        panelRepository.updateById(panel);
                    }
                    log.info("视频生成完成: panelId={}, taskId={}", panelId, taskId);
                    return;
                case "failed":
                    updatePanelProductionState(panelId, "videoStatus", "failed", status.getErrorMessage());
                    return;
                default:
                    Thread.sleep(5000);
                    break;
            }
        }
        updatePanelProductionState(panelId, "videoStatus", "failed", "视频生成超时");
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } catch (Exception e) {
        log.error("视频任务轮询异常: panelId={}, taskId={}", panelId, taskId, e);
    }
}

/**
 * 重试失败的视频生成
 */
public void retryVideo(Long panelId) {
    Panel panel = panelRepository.selectById(panelId);
    if (panel == null) {
        throw new BusinessException("分镜不存在");
    }
    Map<String, Object> info = panel.getPanelInfo();
    String videoStatus = getStringFromInfo(info, "videoStatus");
    if (!"failed".equals(videoStatus)) {
        throw new BusinessException("当前状态不可重试，仅支持重试失败的视频");
    }
    info.put("videoStatus", "pending");
    info.put("errorMessage", null);
    panel.setPanelInfo(info);
    panelRepository.updateById(panel);

    generateVideo(panelId);
}
```

需要新增 import：
```java
import com.comic.dto.response.VideoStatusResponse;
```

- [ ] **Step 2: 在 PanelController 添加视频接口**

```java
// ================= AI 视频生成 =================

@GetMapping("/{panelId}/video")
@Operation(summary = "获取视频状态")
public Result<VideoStatusResponse> getVideoStatus(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    return Result.ok(panelProductionService.getVideoStatus(panelId));
}

@PostMapping("/{panelId}/video")
@Operation(summary = "生成视频（四宫格 → 视频大模型）")
public Result<Void> generateVideo(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    panelProductionService.generateVideo(panelId);
    return Result.ok();
}

@PostMapping("/{panelId}/video/retry")
@Operation(summary = "重试失败的视频生成")
public Result<Void> retryVideo(
        @PathVariable String projectId,
        @PathVariable Long episodeId,
        @PathVariable Long panelId) {
    panelProductionService.retryVideo(panelId);
    return Result.ok();
}
```

需要新增 import：
```java
import com.comic.dto.response.VideoStatusResponse;
```

- [ ] **Step 3: Commit**

```bash
git add controller/PanelController.java service/production/PanelProductionService.java
git commit -m "feat: add video generation endpoints to PanelController"
```

---

## Task 13: 删除 StoryController 和 GridSplitService + 清理 EpisodeProductionService

**Files:**
- Delete: `controller/StoryController.java`
- Delete: `service/production/GridSplitService.java`
- Modify: `service/production/EpisodeProductionService.java`（移除 GridSplitService 引用，标记 @Deprecated）
- Modify: `service/production/PanelProductionService.java`（标记旧方法为 @Deprecated）

**StoryController 未迁移接口说明：**
StoryController 中以下接口不在 Spec 范围内，删除后不再提供 HTTP 入口（Service 方法保留，供 PipelineService 内部调用）：
- `POST /api/story/start-storyboard` → 由 PipelineService 内部调用 `storyboardService.startStoryboardGeneration`
- `POST /api/story/start-production` → 由 PipelineService 内部调用 `storyboardService.startProductionFromStoryboard`
- `GET /api/story/storyboard/{episodeId}` → 分镜数据可通过 `GET /episodes/{episodeId}` 的 `episodeInfo.storyboardJson` 获取

- [ ] **Step 1: 确认没有其他文件依赖 StoryController 和 GridSplitService**

```bash
grep -r "StoryController\|GridSplitService" --include="*.java" backend/com/comic/src/ | grep -v "StoryController.java" | grep -v "GridSplitService.java"
```

- [ ] **Step 2: 在 EpisodeProductionService 上添加 @Deprecated 并移除 GridSplitService**

1. 在类上添加 `@Deprecated` 注解和注释说明（后续迭代将整体删除）
2. 移除 `private final GridSplitService gridSplitService;` 字段
3. 移除或注释掉 `splitGridPageForFusion` 方法（该方法依赖 GridSplitService，且旧的 EpisodeController 已在 Task 4 中重写）

- [ ] **Step 3: 在 PanelProductionService 中标记旧方法为 @Deprecated**

旧方法（使用 episodeId + panelIndex 参数）添加 `@Deprecated` 注解：
- `getBackgroundStatus(Long episodeId, Integer panelIndex)`
- `doGenerateBackground(Long episodeId, Integer panelIndex)`
- `getFusionStatus(Long episodeId, Integer panelIndex)`
- `doGenerateFusion(Long episodeId, Integer panelIndex, FusionRequest request)`
- `getTransitionStatus(Long episodeId, Integer panelIndex)`
- `doGenerateTransition(Long episodeId, Integer panelIndex, TransitionRequest request)`
- `getTailFrame(Long episodeId, Integer panelIndex)`
- `getVideoTaskStatus(Long episodeId, Integer panelIndex)`
- `produceSinglePanel(Long episodeId, Integer panelIndex, ProduceRequest request)`
- `produceAllPanels(Long episodeId, Integer startFrom)`
- `synthesizeEpisode(Long episodeId)`
- `getPanelProductionStatus(Long episodeId, Integer panelIndex)`

添加注释：`// TODO: 后续迭代删除 — 已被 panelId 系列方法替代`

- [ ] **Step 4: 删除文件**

```bash
rm backend/com/comic/src/main/java/com/comic/controller/StoryController.java
rm backend/com/comic/src/main/java/com/comic/service/production/GridSplitService.java
```

- [ ] **Step 5: 编译验证**

```bash
cd backend/com/comic && ./mvnw compile -q
```

如果有编译错误，修复后重新编译。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: delete StoryController and GridSplitService, deprecate old production methods"
```

---

## Task 14: 最终验证与清理

**Files:**
- Various (编译修复)

- [ ] **Step 1: 全量编译**

```bash
cd backend/com/comic && ./mvnw compile -q
```

- [ ] **Step 2: 检查所有旧路由是否已清理**

```bash
grep -r "api/episodes/\|api/story" --include="*.java" backend/com/comic/src/ | grep -v "projects/{projectId}/episodes"
```

确认没有残留的旧路由引用。

- [ ] **Step 3: 确认接口总数**

- EpisodeController: 5 个接口 (GET list, GET detail, POST, PUT, DELETE)
- PanelController: 22 个接口 (5 CRUD + 5 generate + 2 status + 3 background + 4 comic + 3 video)
- 合计: 27 个接口

- [ ] **Step 4: Commit（如有修复）**

```bash
git add -A
git commit -m "chore: final cleanup and compile verification"
```

---

## 依赖关系

```
Task 1 (DTOs) ─────────────────────────┐
Task 2 (Repository) ── Task 3 (Service) ── Task 4 (Controller Rewrite)
                                        │
Task 5 (Panel DTOs) ── Task 6 (Panel Repo+Service) ── Task 7 (Response Rewrite)
                                                              │
                                                              ├── Task 8 (PanelController CRUD+Generate)
                                                              │
                                                              ├── Task 9 (Production Status)
                                                              │
                                                              ├── Task 10 (Background)
                                                              │
                                                              ├── Task 11 (Comic)
                                                              │
                                                              └── Task 12 (Video)
                                                                    │
                                                              Task 13 (Delete Old)
                                                                    │
                                                              Task 14 (Final Verify)
```

Task 1-4 (Episode) 和 Task 5-7 (Panel 基础) 可以并行。Task 8-12 (Panel 生产) 依赖 Task 7。