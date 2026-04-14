# 参考图视频模式设计规格

**日期**: 2026-04-14
**状态**: 待用户确认

---

## 1. 背景与目标

现有视频生成流程为 **首帧视频（img2video）**：九宫格 → 切割 → 融合成单张参考图 → 送入 Vidu 图生视频 API。

本次新增 **参考图视频（reference2video）** 路线：九宫格 → 切割 → **直接拼接最多7张切分图（+角色图）** → 送入 Vidu 参考视频 API，支持 viduq3 / viduq3-mix 两种模型。流程共享 Step 4 的脚本/九宫格阶段，仅视频生成阶段调用不同接口。

### 关键决策

- 参考图视频模式下跳过融合图生成，`fusionImageUrl=null`，视频生成时从 `episodeInfo.splitShots` 读取切分图。
- 九宫格审核流程（4b）保持不变。
- 视频生成均在视频 Tab（4c）手动触发。

---

## 2. 整体流程

```
Step 4a 脚本生成 ─────────────────────────────────────────────┐
Step 4b 九宫格生成 ──── 审核 ──→ 4c视频Tab                   │
                                                              ↓
                                              videoRefMode=true?
                                                ├─ 是 → reference2video（不走融合图）
                                                └─ 否 → img2video（现有逻辑）
```

---

## 3. 后端设计

### 3.1 新增 `ViduReference2VideoService`

**文件**: `backend/.../ai/video/ViduReference2VideoService.java`

实现 `VideoGenerationService` 接口，调用 `POST https://api.vidu.com/ent/v2/reference2video`。

#### 核心方法签名

```java
@Service
@Slf4j
public class ViduReference2VideoService implements VideoGenerationService {

    // images: 最多7张参考图URL，按优先级排序；characterNames: 角色名列表（后半段对应角色图）
    @Override
    public String generateAsyncMultiImage(String prompt, int duration, String aspectRatio,
                                         List<String> referenceImages,
                                         List<String> characterNames,
                                         boolean offPeak, String model);

    @Override
    public TaskStatus getTaskStatus(String taskId);
    @Override
    public String downloadVideo(String taskId);
    @Override
    public String getServiceName();
}
```

> 注：`VideoGenerationService` 接口当前 `generateAsync` 签名为 `(prompt, duration, aspectRatio, referenceImage, offPeak, model)`，单图传入。扩展接口增加 `generateAsyncMultiImage` 方法（含 `characterNames` 参数用于构建带角色名的 prompt）。

#### 扩展接口方法

```java
// VideoGenerationService 接口新增方法（含角色名，用于构建带名字的 prompt）
public interface VideoGenerationService {
    String generateAsync(String prompt, int duration, String aspectRatio,
                        String referenceImage, boolean offPeak, String model);

    // 新增：多图参考视频生成（含角色名列表，生成带名字的参考图说明段落）
    default String generateAsyncMultiImage(String prompt, int duration, String aspectRatio,
                                          List<String> referenceImages,
                                          List<String> characterNames, // 角色名列表，与 referenceImages 后半段对应
                                          boolean offPeak, String model) {
        throw new UnsupportedOperationException();
    }

    TaskStatus getTaskStatus(String taskId);
    String downloadVideo(String taskId);
    String getServiceName();
}
```

#### 图片优先级规则（最多7张）

**数组排列顺序**：分镜图在前，角色图在后。

```
images[0] = 分镜图1（splitShots[0].splitImageUrl）
images[1] = 分镜图2（splitShots[1].splitImageUrl）
images[2] = 分镜图3（splitShots[2].splitImageUrl）
images[3] = 角色图-角色名1（主角，优先）
images[4] = 角色图-角色名2（反派）
images[5] = 角色图-角色名3（配角）
images[6] = 角色图-角色名4（其他）
```

**优先级规则**：

1. 分镜图优先，最多取 `min(3, splitShots.size())` 张
2. 剩余槽位用角色参考图补足（主角 > 配角 > 其他）
3. 最多共 **7 张**
4. 总数不足 1 张时抛出异常

#### 提示词格式规范

**prompt 中图片引用约定**：

```
分镜图1 / 分镜图2 / 分镜图3 → images[0] / images[1] / images[2]
角色图-李四 / 角色图-张三 ... → images[3] / images[4] / images[5] / images[6]
```

> **关键**：角色图必须带真实角色名（如"角色图-李四"），不得使用编号。角色名从 `Character.name` 字段读取。

**视频提示词示例**：

> 角色图-李四和角色图-张三在室内场景对峙，双方激烈打斗，动作流畅，镜头从分镜图1拉远至分镜图2。

**自动构建提示词的方法**：

`ViduReference2VideoService` 在组装 prompt 时自动附加参考图说明段落。`buildReferenceImageContext` 接收角色名列表，输出带真实角色名的参考图说明：

```java
private String buildReferenceImageContext(List<Map<String, Object>> panelShots,
                                        List<CharRef> charRefs) {
    // charRefs 来自 GridImageService.getCharacterReferencesWithNamesForEpisode(episodeId)
    // CharRef 包含 url 和 name 字段
    StringBuilder sb = new StringBuilder();
    sb.append("【参考图说明】\n");

    // 分镜图说明（最多3张）
    int shotCount = Math.min(3, panelShots.size());
    for (int i = 0; i < shotCount; i++) {
        Map<String, Object> shot = panelShots.get(i);
        String desc = (String) shot.getOrDefault("visualDescription", "");
        sb.append(String.format("分镜图%d: %s\n", i + 1, desc));
    }

    // 角色图说明（从 images[3] 开始，角色名必须带名字）
    for (int i = 0; i < charRefs.size(); i++) {
        String charName = charRefs.get(i).getName();
        sb.append(String.format("角色图-%s: 角色参考图\n", charName));
    }

    sb.append("【视频指令】\n");
    return sb.toString();
}
```

最终 prompt = `buildReferenceImageContext(...)` + 镜头指令段落（复用 `PanelPromptBuilder.buildMultiShotPrompt` 的镜头部分）。

#### API 参数映射


| Vidu API 参数    | 来源                                               |
| -------------- | ------------------------------------------------ |
| `model`        | 项目 `projectInfo.videoModel`（viduq3 / viduq3-mix） |
| `images`       | splitShots 分镜图 + 角色图拼接，最多7张                      |
| `prompt`       | 同现有逻辑，从 `panelInfo` 取                            |
| `duration`     | 同现有逻辑，从 shots 推算（viduq3: 3-16, viduq3-mix: 1-16） |
| `resolution`   | viduq3: 540p/720p/1080p, viduq3-mix: 720p/1080p  |
| `aspect_ratio` | 项目配置，默认 16:9                                     |
| `off_peak`     | 用户选择（仅 viduq3 支持，viduq3-mix 强制 false）            |
| `watermark`    | false                                            |


### 3.2 `PanelProductionService` 变更

新增方法：

```java
// 参考图视频生成（videoRefMode=true 时调用）
public void generateVideoRefByPanelId(Long panelId, boolean offPeak,
                                      String customPrompt, String videoModel)
```

修改 `doGenerateVideoByPanelId` 增加判断分支：

```java
// 从项目配置读取 videoRefMode
Project project = projectRepository.findByProjectId(projectId);
boolean videoRefMode = project != null
    && Boolean.TRUE.equals(project.getProjectInfo().get("videoRefMode"));

if (videoRefMode) {
    // 参考图视频模式：收集 splitShots 参考图 + 角色名
    Pair<List<String>, List<String>> refPair = collectReferenceImagesWithNames(panel);
    ViduReference2VideoService svc = applicationContext.getBean(ViduReference2VideoService.class);
    String taskId = svc.generateAsyncMultiImage(
        prompt, duration, aspectRatio,
        refPair.getLeft(),    // 参考图 URL 列表
        refPair.getRight(),   // 角色名列表（用于构建带名字的 prompt）
        offPeak, model);
    // ... 后续轮询逻辑复用
} else {
    // 现有 img2video 逻辑
    videoService.generateAsync(prompt, duration, aspectRatio, fusionImageUrl, offPeak, model);
}
```

新增私有方法：

```java
/**
 * 收集参考图及角色名：分镜图优先（最多3张）+ 角色图补足至7张
 * 返回 Pair<List<String>, List<String>>: urls 和对应角色名
 */
private Pair<List<String>, List<String>> collectReferenceImagesWithNames(Panel panel) {
    Long episodeId = panel.getEpisodeId();

    // 1. 从 episodeInfo.splitShots 取分镜图
    Episode episode = episodeRepository.selectById(episodeId);
    List<Map<String, Object>> splitShots = (List<Map<String, Object>>)
        episode.getEpisodeInfo().get("splitShots");
    List<String> refImageUrls = new ArrayList<>();
    List<String> charNames = new ArrayList<>();

    if (splitShots != null) {
        int shotCount = Math.min(3, splitShots.size());
        for (int i = 0; i < shotCount; i++) {
            String url = (String) splitShots.get(i).get("splitImageUrl");
            if (url != null) refImageUrls.add(url);
        }
    }

    // 2. 角色图补足至7张（含角色名）
    List<CharRef> charRefs = gridImageService.getCharacterReferencesWithNamesForEpisode(episodeId);
    for (CharRef cr : charRefs) {
        if (refImageUrls.size() >= 7) break;
        if (!refImageUrls.contains(cr.getUrl())) {
            refImageUrls.add(cr.getUrl());
            charNames.add(cr.getName());
        }
    }

    if (refImageUrls.isEmpty()) {
        throw new BusinessException("参考图数量不足，无法生成视频");
    }
    return Pair.of(refImageUrls, charNames);
}
```

### 3.3 `EpisodeController.approveEpisodeGrid` 变更

**文件**: `backend/.../controller/EpisodeController.java`

在 Panel 创建逻辑中增加 `videoRefMode` 判断：

```java
// 判断项目是否使用参考图视频模式
Project project = projectRepository.findByProjectId(projectId);
boolean videoRefMode = project != null
    && Boolean.TRUE.equals(project.getProjectInfo().get("videoRefMode"));

// 每组创建 Panel
for (List<Map<String, Object>> group : groups) {
    Panel panel = new Panel();
    panel.setEpisodeId(episodeId);
    panel.setStatus("pending");
    panel.setDeleted(false);
    Map<String, Object> panelInfo = new HashMap<>();
    panelInfo.put("shots", group);
    panelInfo.put("totalShots", group.size());
    panelInfo.put("totalDuration", ...);
    panelInfo.put("gridStatus", "approved");
    panelInfo.put("videoStatus", "pending");
    panelInfo.put("visualStyle", visualStyle);
    panelInfo.put("gridImages", new ArrayList<String>());
    panelInfo.put("videoRefMode", videoRefMode); // 标记模式

    if (!videoRefMode) {
        // 现有逻辑：为每个 Panel 生成融合图
        BufferedImage fusionImage = gridImageService.createFusionImageForPanelWithNames(group, charRefsWithNames);
        String fusionUrl = gridImageService.uploadFusionImageForPanel(fusionImage, episodeId, group);
        panelInfo.put("fusionImageUrl", fusionUrl);
    } else {
        // 参考图视频模式：跳过融合图
        panelInfo.put("fusionImageUrl", null);
    }

    panel.setPanelInfo(panelInfo);
    panelRepository.insert(panel);
}
```

### 3.4 新增 API 端点

```
POST /api/projects/{projectId}/episodes/{episodeId}/panels/{panelId}/video-ref
Content-Type: application/json
Body: {
    "offPeak": boolean,       // 错峰模式（仅 viduq3 有效）
    "customPrompt": string,    // 自定义提示词（可选）
    "videoModel": string      // viduq3 / viduq3-mix（可选）
}

Response: { code: 0, message: "ok", data: null }
```

现有 `POST .../video` 接口保持不变，在 `PanelProductionService` 内部根据 `panelInfo.videoRefMode` 判定走哪个分支。

---

## 4. 前端设计

### 4.1 Step 1 — 新增视频模式选项

**文件**: `frontend/.../steps/Step1Content.tsx`

在视频提供商下方、模型选择上方，新增单选按钮组：

```tsx
// 视频模式选项
const videoModeOptions = [
  { value: 'first_frame', label: '首帧视频' },
  { value: 'reference_images', label: '参考图视频' },
];
```

当 `videoProvider === 'vidu'` 时显示此选项。

**选中"参考图视频"时的联动行为**：

1. 模型自动切换为 `viduq3-mix`（仅提示，用户仍可手动改）
2. 隐藏错峰开关（viduq3-mix 不支持）
3. 将 `videoRefMode: true` 写入项目 `projectInfo`

**API 请求变更**：`CreateProjectRequest` 增加可选字段 `videoRefMode?: boolean`

### 4.2 Step 4 视频 Tab — 差异化渲染

**文件**: `frontend/.../steps/Step4Production.tsx`

当 `project?.projectInfo?.videoRefMode === true` 时：


| 差异点      | 现有模式              | 参考图视频模式              |
| -------- | ----------------- | -------------------- |
| 提示词润色按钮  | 显示                | **隐藏**               |
| 融合参考图模态框 | 显示 fusionImageUrl | **隐藏**，改为显示参考图列表     |
| 视频生成调用   | `generateVideo()` | `generateVideoRef()` |
| 错峰开关     | 显示                | **隐藏**               |
| 视频模式标识   | 无                 | 显示徽章"参考图视频"          |
| 参考图来源    | 无                 | `splitShots` 切分图     |


**参考图列表 UI**：
在 VideoSegmentRow 展开详情中，当 `videoRefMode=true` 时，在左侧区域显示：

- 参考图数量标签：`[参考图: 3张分镜 + N张角色]`
- 点击展开显示7张缩略图（分镜图 + 角色图合并展示）

### 4.3 前端服务变更

**文件**: `frontend/.../services/episodeService.ts`

新增：

```typescript
/** 参考图视频模式生成视频 */
export async function generateVideoRef(
  projectId: string,
  episodeId: number,
  panelId: number,
  offPeak: boolean = false,
  customPrompt?: string,
  videoModel?: string,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video-ref`,
    { offPeak, customPrompt, videoModel },
  );
}
```

---

## 5. 项目配置存储


| 字段                         | 类型      | 说明                    |
| -------------------------- | ------- | --------------------- |
| `projectInfo.videoRefMode` | boolean | 是否使用参考图视频模式           |
| `projectInfo.videoModel`   | string  | viduq3 / viduq3-mix 等 |
| `panelInfo.videoRefMode`   | boolean | Panel 级模式标记（从项目继承）    |


---

## 6. 错误处理


| 场景                   | 处理方式                                    |
| -------------------- | --------------------------------------- |
| 参考图不足1张              | 抛出 BusinessException，前端提示"参考图数量不足"      |
| 参考图超过7张              | 取前7张（分镜图优先），日志警告                        |
| viduq3-mix + offPeak | 后端强制 offPeak=false，前端隐藏开关               |
| API 返回 failed        | 同现有逻辑，更新 `panelInfo.videoStatus=failed` |
| viduq3-mix + 竖屏 9:16 | viduq3-mix 支持 9:16，传递即可                 |


---

## 7. 文件变更清单

### 后端（新增）

- `backend/.../ai/video/ViduReference2VideoService.java`
- `backend/.../controller/PanelController.java`（新增 video-ref 端点）
- `backend/.../ai/video/VideoGenerationService.java`（接口新增 generateAsyncMultiImage 默认方法）

### 后端（修改）

- `backend/.../service/production/PanelProductionService.java`（新增 generateVideoRefByPanelId、分支判断、collectReferenceImages）
- `backend/.../controller/EpisodeController.java`（approveEpisodeGrid 增加 videoRefMode 判断，跳过融合图）

### 前端（新增）

- `frontend/.../services/episodeService.ts`（新增 generateVideoRef）

### 前端（修改）

- `frontend/.../steps/Step1Content.tsx`（新增视频模式选项）
- `frontend/.../steps/Step4Production.tsx`（videoRefMode 差异化渲染）
- `frontend/.../services/types/episode.types.ts`（CreateProjectRequest 增加 videoRefMode）

---

## 8. 实现顺序建议

1. **Phase 1**: 后端 `ViduReference2VideoService` + `VideoGenerationService` 接口扩展
2. **Phase 2**: 后端 `PanelProductionService` 新增方法 + `EpisodeController` 融合图跳过逻辑
3. **Phase 3**: 前端 Step1 视频模式选项 + 类型定义
4. **Phase 4**: 前端 Step4 差异化渲染 + `generateVideoRef` 调用
5. **Phase 5**: 集成测试 + 回归测试

