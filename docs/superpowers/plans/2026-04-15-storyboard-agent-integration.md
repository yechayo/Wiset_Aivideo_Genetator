# Storyboard Agent 集成实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 StoryboardAgentService（ReAct 循环分批生成分镜）集成到 PanelProductionService，替换原有的一次性生成逻辑。

**Architecture:** 在 `AiServiceConfiguration` 中注册一个 Reasoner 模型的 `DeepSeekTextService` Bean，用 `@Qualifier` 区分。在 `generateStoryboardForEpisode()` 中，精修模式走原有路径，非精修模式走 agent 循环。Agent 生成的 flat shots 需要为漫剧模式包装成 panelGroups 格式以兼容后续旁白精修流程。

**Tech Stack:** Java 17, Spring Boot, DeepSeek API (deepseek-reasoner + deepseek-chat), JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-04-15-storyboard-agent-design.md`

**已有代码（分支 `feat/storyboard-agent` 上已实现）：**
- `StoryboardAgentService.java` — Agent 循环主体 + 22 个单元测试全部通过
- 需要集成：Reasoner Bean、yml 配置、PanelProductionService 接入

---

## 文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| `config/AiServiceConfiguration.java` | 修改 | 新增 Reasoner Bean（deepseek-reasoner 模型） |
| `application.yml` | 修改 | 新增 `comic.deepseek-reasoner` 配置段 |
| `service/production/StoryboardAgentService.java` | 修改 | 构造器加 `@Qualifier` 区分两个 Bean |
| `service/production/PanelProductionService.java` | 修改 | `generateStoryboardForEpisode()` 接入 agent |

---

### Task 1: 添加 Reasoner 配置到 application.yml

**Files:**
- Modify: `backend/com/comic/src/main/resources/application.yml`

- [ ] **Step 1: 在 `comic.deepseek` 配置段后面添加 Reasoner 配置**

在 `application.yml` 中 `comic.deepseek` 段之后添加 `comic.deepseek-reasoner` 配置段。当前 `comic.deepseek` 段在约第 139 行结束。

```yaml
  # ========== DeepSeek Reasoner 配置（分镜 Agent 规划用） ==========
  deepseek-reasoner:
    api-key: ${DEEPSEEK_API_KEY:}      # 复用同一 key
    base-url: https://api.deepseek.com
    model: deepseek-reasoner
    max-tokens: 4096
```

注意：此段与 `comic.deepseek` 是**平级**的（都在 `comic:` 下），不是嵌套在 `deepseek:` 内部。

- [ ] **Step 2: 验证 YAML 格式正确**

Run: `cd backend/com/comic && mvn spring-boot:validate -q 2>&1 | head -5`
Expected: 无 YAML 解析错误

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/resources/application.yml
git commit -m "chore: add deepseek-reasoner config for storyboard agent"
```

---

### Task 2: 在 AiServiceConfiguration 注册 Reasoner Bean

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/config/AiServiceConfiguration.java`

**背景：** `DeepSeekTextService` 使用 `@Value("${comic.deepseek.*}")` 注入配置，所以同一个类只能绑定一个配置前缀。要创建一个使用 Reasoner 模型的实例，需要在 `@Bean` 方法中手动创建并通过 `Environment` 读取配置。

- [ ] **Step 1: 添加 Reasoner Bean**

在 `AiServiceConfiguration.java` 中添加 imports 和 Reasoner Bean 方法。

在文件顶部添加 import：
```java
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import com.comic.ai.text.DeepSeekTextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
```

注意：部分 import 可能已存在，只添加缺少的。检查现有 import 后按需添加。

在类中添加以下 Bean 方法（放在 `textGenerationService` 方法之后）：

```java
/**
 * DeepSeek Reasoner Bean — 用于分镜 Agent 的规划决策（deepseek-reasoner 模型）
 */
@Bean
@Qualifier("reasoner")
public DeepSeekTextService deepseekReasonerTextService(OkHttpClient httpClient,
                                                        ObjectMapper objectMapper,
                                                        Environment env) {
    DeepSeekTextService reasoner = new DeepSeekTextService(httpClient, objectMapper);
    // 通过 Spring 反射设置 @Value 字段（因为 @Value 只对 Spring 管理的 Bean 生效）
    try {
        setField(reasoner, "apiKey", env.getProperty("comic.deepseek-reasoner.api-key", ""));
        setField(reasoner, "baseUrl", env.getProperty("comic.deepseek-reasoner.base-url", "https://api.deepseek.com"));
        setField(reasoner, "model", env.getProperty("comic.deepseek-reasoner.model", "deepseek-reasoner"));
        setField(reasoner, "maxTokens", Integer.parseInt(env.getProperty("comic.deepseek-reasoner.max-tokens", "4096")));
        // Reasoner 不需要旁白精修功能，显式设为 false
        setField(reasoner, "narrationRefinementEnabled", false);
    } catch (Exception e) {
        throw new RuntimeException("Failed to configure DeepSeek Reasoner", e);
    }
    log.info("DeepSeek Reasoner 配置完成: model={}", env.getProperty("comic.deepseek-reasoner.model", "deepseek-reasoner"));
    return reasoner;
}

private void setField(Object target, String fieldName, Object value) throws Exception {
    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/config/AiServiceConfiguration.java
git commit -m "feat: register DeepSeek Reasoner bean for storyboard agent"
```

---

### Task 3: StoryboardAgentService 构造器加 @Qualifier

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java`

- [ ] **Step 1: 添加 @Qualifier 注解**

添加 import：
```java
import org.springframework.beans.factory.annotation.Qualifier;
```

修改构造器，用 `@Qualifier` 区分 Reasoner 和 Executor：

```java
public StoryboardAgentService(@Qualifier("reasoner") DeepSeekTextService reasoner,
                               @Qualifier("textGenerationService") DeepSeekTextService executor,
                               ObjectMapper objectMapper) {
    this.reasoner = reasoner;
    this.executor = executor;
    this.objectMapper = objectMapper;
}
```

说明：
- `@Qualifier("reasoner")` → Task 2 注册的 Reasoner Bean
- `@Qualifier("textGenerationService")` → 原有的 `@Primary` Bean（deepseek-chat 模型）

- [ ] **Step 2: 编译验证**

Run: `cd backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: 运行已有测试确保无回归**

Run: `cd backend/com/comic && mvn test -pl . -Dtest=StoryboardAgentServiceTest -q 2>&1 | tail -10`
Expected: Tests run: 22, Failures: 0

注意：单元测试使用 Mockito mock，不走 Spring 容器，所以 `@Qualifier` 不影响测试。

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/StoryboardAgentService.java
git commit -m "feat: add @Qualifier to StoryboardAgentService constructor"
```

---

### Task 4: PanelProductionService 接入 Agent

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java`

**核心逻辑：**
- 精修模式（`isRefinement && lockedShots 非空`）→ 走原有 `deepSeekTextService.generateStoryboard()` 路径，不变
- 非精修模式 → 走 `storyboardAgentService.generate()`
- 漫剧模式 agent 生成的 flat shots 需要包装成 `List<List<Map>>` panelGroups 格式，兼容后续旁白精修

- [ ] **Step 1: 添加 StoryboardAgentService 字段和构造器参数**

在 `PanelProductionService.java` 中：

1. 添加 import（在文件顶部 import 区域）：
```java
import com.comic.service.production.StoryboardAgentService;
```

2. 添加字段（在 `deepSeekTextService` 字段旁边，约第 66 行）：
```java
private final StoryboardAgentService storyboardAgentService;
```

3. 修改构造器，添加参数（在 `DeepSeekTextService deepSeekTextService` 参数后面）：
```java
PanelProductionService(...原有参数...,
                       DeepSeekTextService deepSeekTextService,
                       StoryboardAgentService storyboardAgentService,
                       ProgressService progressService,
                       ...) {
    ...
    this.deepSeekTextService = deepSeekTextService;
    this.storyboardAgentService = storyboardAgentService;
    ...
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "refactor: add StoryboardAgentService dependency to PanelProductionService"
```

---

### Task 5: 替换 generateStoryboardForEpisode 中的生成逻辑

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java:1478-1530`

- [ ] **Step 1: 替换分镜生成核心逻辑**

在 `generateStoryboardForEpisode()` 方法中，找到 `List<Map<String, Object>> shots;` 和 `try {` 开始的代码块（约第 1478-1530 行），替换为以下逻辑：

```java
        List<Map<String, Object>> shots;
        try {
            if (isRefinement && lockedShots != null && !lockedShots.isEmpty()) {
                // 精修模式（有锁定分镜）→ 走原有路径，不经过 agent
                log.info("[Pipeline-Text] 精修模式(lockedShots={}), 走原有生成路径", lockedShots.size());
                if (comicMode) {
                    String narrationPerspective = (String) projectInfo.get("narrationPerspective");
                    List<List<Map<String, Object>>> panelGroups =
                        deepSeekTextService.generatePanelAwareStoryboard(
                            content, characters, targetDuration, visualStyle, revisionNote, narrationPerspective, lockedShots);
                    shots = new ArrayList<>();
                    for (List<Map<String, Object>> panelShots : panelGroups) {
                        shots.addAll(panelShots);
                    }
                } else {
                    shots = deepSeekTextService.generateStoryboard(
                        content, characters, targetDuration, visualStyle, false, revisionNote, lockedShots);
                }
            } else {
                // 非精修模式 → 走 Agent 循环
                String narrationPerspective = (String) projectInfo.get("narrationPerspective");
                log.info("[Pipeline-Text] Agent 模式: episode={}({}), target={}s, comicMode={}",
                        episodeNum, title, targetDuration, comicMode);

                shots = storyboardAgentService.generate(
                        content, characters, targetDuration, visualStyle, comicMode, narrationPerspective);

                // Agent 返回空 shots 防护
                if (shots.isEmpty()) {
                    throw new RuntimeException("分镜 Agent 未生成任何分镜: episode=" + episodeNum + " (" + title + ")");
                }

                // 漫剧模式：将 flat shots 包装成 panelGroups（每个 shot 独立一个 panel）
                // 兼容后续旁白精修流程 (Stage 2 refineNarrationsSequentially)
                if (comicMode) {
                    List<List<Map<String, Object>>> panelGroups = new ArrayList<>();
                    for (Map<String, Object> shot : shots) {
                        List<Map<String, Object>> panel = new ArrayList<>();
                        panel.add(shot);
                        panelGroups.add(panel);
                    }

                    // Stage 2: 旁白精修
                    if (deepSeekTextService.isNarrationRefinementEnabled()) {
                        try {
                            panelGroups = deepSeekTextService.refineNarrationsSequentially(
                                panelGroups, content, narrationPerspective);
                            log.info("[Pipeline-Text] Stage 2 旁白精修完成: projectId={}, episode={}", projectId, title);
                        } catch (Exception e) {
                            log.warn("[Pipeline-Text] Stage 2 旁白精修失败，保留原始旁白: projectId={}, episode={}, error={}",
                                    projectId, title, e.getMessage());
                        }
                    }

                    shots = new ArrayList<>();
                    for (List<Map<String, Object>> panelShots : panelGroups) {
                        shots.addAll(panelShots);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[Pipeline-Text] 分镜生成失败: projectId={}, episode={}, error={}", projectId, title, e.getMessage(), e);
            throw new RuntimeException("分镜生成失败(第" + episodeNum + "集 " + title + "): " + e.getMessage(), e);
        }
```

**关键设计决策：**
1. 精修模式不走 agent → agent 无法处理已锁定的分镜
2. 漫剧模式下 agent 生成的 flat shots 每个包成单元素 panelGroup → 兼容旁白精修
3. 旁白精修只在漫剧模式下执行 → 与原逻辑一致
4. Agent 返回空 shots → 立即抛异常，避免创建空 episode

**已知注意事项（非阻塞，后续迭代可优化）：**
- 漫剧模式下每个 shot 独立一个 panel，旁白精修缺少多 shot panel 的上下文，可能影响连贯性
- Reasoner Bean 通过反射设置 `@Value` 字段，如果 `DeepSeekTextService` 字段名重构需同步修改
- 无 feature flag，回滚需改代码。如需快速关闭 agent，将 `if` 条件改为 `if (true)` 即可走回原有路径

- [ ] **Step 2: 编译验证**

Run: `cd backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/PanelProductionService.java
git commit -m "feat: integrate storyboard agent into episode generation pipeline"
```

---

### Task 6: 全量编译 + 测试验证

- [ ] **Step 1: 全量编译**

Run: `cd backend/com/comic && mvn compile -q 2>&1 | tail -10`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行所有测试**

Run: `cd backend/com/comic && mvn test -q 2>&1 | tail -15`
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 3: 单独运行 StoryboardAgentService 测试**

Run: `cd backend/com/comic && mvn test -Dtest=StoryboardAgentServiceTest -q 2>&1 | tail -5`
Expected: Tests run: 22, Failures: 0

---

### Task 7: 合并到 main

- [ ] **Step 1: 切换到 main 并合并**

```bash
git checkout main
git merge feat/storyboard-agent
```

- [ ] **Step 2: 验证合并后编译通过**

Run: `cd backend/com/comic && mvn compile -q 2>&1 | tail -5`
Expected: BUILD SUCCESS
