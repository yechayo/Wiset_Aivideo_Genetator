# AI 服务架构设计文档

## 📋 概述

本系统采用**按功能分类的多模型 AI 服务架构**，不同功能使用不同的 AI 模型，实现最佳性价比和效果。

---

## 🎯 核心设计原则

1. **按功能分类**：文本、图片、视频各自独立
2. **可配置化**：通过配置文件灵活切换模型
3. **并发控制**：每个服务独立的并发限制
4. **统一接口**：相同功能的不同实现共用同一接口

---

## 🏗️ 架构层次

```
┌─────────────────────────────────────────────────────┐
│              业务层 (Service)                         │
│  ScriptService | CharacterService | VideoService      │
└──────────────────┬────────────────────────────────────┘
                   │
┌──────────────────▼────────────────────────────────────┐
│            AI 服务接口层 (Interface)                   │
│  TextGenerationService | ImageGenerationService        │
│  VideoGenerationService                                │
└──────────────────┬────────────────────────────────────┘
                   │
┌──────────────────▼────────────────────────────────────┐
│            AI 服务实现层 (Implementation)               │
│  DeepSeekTextService | GlmImageService                │
│  UniversalVideoService                                 │
└──────────────────┬────────────────────────────────────┘
                   │
┌──────────────────▼────────────────────────────────────┐
│              第三方 AI API                             │
│  DeepSeek API | GLM CogView | Video APIs               │
└───────────────────────────────────────────────────────┘
```

---

## 📦 服务接口定义

### 1. TextGenerationService（文本生成）

**用途**：剧本生成、对话生成、世界观创建等文本类任务

**接口方法**：
```java
String generate(String systemPrompt, String userPrompt);
String generateStream(String systemPrompt, String userPrompt);
int getAvailableConcurrentSlots();
```

**实现类**：
- `DeepSeekTextService` - DeepSeek 文本生成（性价比高）
- `ClaudeTextService` - Claude 文本生成（质量高，备用）

**配置示例**：
```yaml
comic:
  ai:
    text:
      provider: deepseek    # 使用 DeepSeek
      max-concurrent: 5     # 最多5个并发
```

---

### 2. ImageGenerationService（图片生成）

**用途**：角色立绘、分镜图、背景图等图片生成任务

**接口方法**：
```java
String generate(String prompt, int width, int height, String style);
String generateWithReference(String prompt, String refImage, int w, int h);
int getAvailableConcurrentSlots();
```

**实现类**：
- `GlmImageService` - GLM CogView（国产，稳定）
- `StableDiffusionService` - Stable Diffusion（开源，灵活）

**配置示例**：
```yaml
comic:
  ai:
    image:
      provider: glm         # 使用 GLM CogView
      default-style: anime  # 默认动漫风格
```

---

### 3. VideoGenerationService（视频生成）

**用途**：分镜视频、Sora 视频生成等视频类任务

**接口方法**：
```java
String generateAsync(String prompt, int duration, String ar, String refImg);
TaskStatus getTaskStatus(String taskId);
String downloadVideo(String taskId);
```

**实现类**：
- `UniversalVideoService` - 通用视频服务（支持多平台）
- `SoraService` - OpenAI Sora（未来）

**配置示例**：
```yaml
comic:
  ai:
    video:
      provider: yunwu       # 使用云雾平台
      max-concurrent: 1     # 视频生成资源占用大，限制为1
```

---

## ⚙️ 配置文件结构

### application.yml

```yaml
comic:
  ai:
    # 文本生成配置
    text:
      provider: deepseek          # 文本用 DeepSeek
      max-concurrent: 5
      timeout-seconds: 120

    # 图片生成配置
    image:
      provider: glm               # 图片用 GLM
      max-concurrent: 2
      default-style: anime
      default-width: 1024
      default-height: 1024

    # 视频生成配置
    video:
      provider: yunwu             # 视频用云雾
      max-concurrent: 1
      default-duration: 5
      default-aspect-ratio: "16:9"

  # 具体模型的 API 配置
  model:
    deepseek:
      api-key: sk-xxx
      base-url: https://api.siliconflow.cn/v1
      model: deepseek-ai/DeepSeek-V3

    glm:
      api-key: xxx
      image-model: cogview-3
```

---

## 🔧 使用示例

### 在 Service 中注入使用

```java
@Service
@RequiredArgsConstructor
public class ScriptService {

    // ✅ 注入文本生成服务（自动使用配置的模型）
    private final TextGenerationService textGenerationService;

    public String generateScript() {
        String systemPrompt = "你是一个专业的剧本作家...";
        String userPrompt = "请帮我创作一个热血玄幻剧本...";

        // 调用（自动使用 DeepSeek 或其他配置的模型）
        String script = textGenerationService.generate(systemPrompt, userPrompt);

        return script;
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class CharacterService {

    // ✅ 注入图片生成服务（自动使用配置的模型）
    private final ImageGenerationService imageGenerationService;

    public String generateCharacterPortrait(String characterDescription) {
        String prompt = "anime style, " + characterDescription;

        // 调用（自动使用 GLM CogView 或其他配置的模型）
        String imageUrl = imageGenerationService.generate(prompt, 1024, 1024, "anime");

        return imageUrl;
    }
}
```

---

## 🎨 优势

### 1. **灵活性**
- 不同功能可使用不同模型
- 文本用 DeepSeek（便宜），图片用 GLM（稳定），视频用专业平台
- 随时可通过配置文件切换，无需改代码

### 2. **成本优化**
- DeepSeek 文本生成：~1元/百万字（性价比极高）
- GLM 图片生成：国产平台，稳定且价格合理
- 视频生成：按需选择平台

### 3. **并发控制**
- 每个服务独立控制并发数
- 文本：5个并发（生成快）
- 图片：2个并发（资源占用中等）
- 视频：1个并发（资源占用大）

### 4. **易于扩展**
- 新增模型实现：只需实现接口，添加配置
- 例如：想用 Midjourney 生成图片，只需创建 `MidjourneyImageService`

---

## 🚀 快速开始

### 1. 修改配置

编辑 `application.yml`：

```yaml
comic:
  ai:
    text:
      provider: deepseek    # 文本用 DeepSeek
    image:
      provider: glm         # 图片用 GLM
    video:
      provider: yunwu       # 视频用云雾
```

### 2. 在 Service 中注入

```java
private final TextGenerationService textGenerationService;
private final ImageGenerationService imageGenerationService;
private final VideoGenerationService videoGenerationService;
```

### 3. 调用方法

```java
// 文本生成
String text = textGenerationService.generate(systemPrompt, userPrompt);

// 图片生成
String image = imageGenerationService.generate(prompt, 1024, 1024, "anime");

// 视频生成
String taskId = videoGenerationService.generateAsync(prompt, 5, "16:9", null);
```

---

## 📊 服务选择建议

| 功能 | 推荐模型 | 理由 | 成本 |
|------|---------|------|------|
| 文本生成 | DeepSeek | 性价比最高，中文优秀 | ~1元/百万字 |
| 图片生成 | GLM CogView | 国产稳定，动漫效果好 | ~0.1元/张 |
| 视频生成 | 云雾/速推 | 支持国内，速度快 | 按平台定价 |

---

## 🔄 迁移指南

### 旧代码（硬编码）
```java
private final ClaudeService claudeService;

String result = claudeService.call(systemPrompt, userPrompt);
```

### 新代码（可配置）
```java
private final TextGenerationService textGenerationService;

String result = textGenerationService.generate(systemPrompt, userPrompt);
// ✅ 自动使用配置文件中设置的模型（DeepSeek）
```

---

## 🎯 总结

通过**按功能分类的多模型 AI 服务架构**，实现了：

1. ✅ **灵活配置** - 不同功能用不同模型
2. ✅ **成本优化** - 选择性价比最高的方案
3. ✅ **易于扩展** - 新增模型无需修改业务代码
4. ✅ **统一接口** - 业务代码无需关心底层实现

**现在你可以**：
- 文本生成用 DeepSeek（便宜）
- 图片生成用 GLM（稳定）
- 视频生成用专业平台

**一键切换**，只需修改配置文件！
