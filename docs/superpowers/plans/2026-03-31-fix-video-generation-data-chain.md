# 视频生成数据链修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Step1→Step5 数据链断裂问题，确保角色信息、剧本大纲正确传递到分镜生成，且生成集数与用户输入一致。

**Architecture:**
1. 修复 `StoryboardService` 读取大纲和角色信息的路径
2. 限制 `DeepSeekTextService` 生成指定集数的剧本
3. 在分镜中注入角色 ID，替代纯名字匹配

**Tech Stack:** Spring Boot, MyBatis-Plus, Java 17

---

## Files Overview

| File | Action | Purpose |
|------|--------|---------|
| `StoryboardService.java` | Modify | 修复大纲读取路径、实现角色描述获取、注入角色ID |
| `DeepSeekTextService.java` | Modify | 添加集数限制参数到 prompt |
| `GridImageService.java` | Modify | 优先使用 charId 匹配角色 |
| `CharacterRepository.java` | Check | 确认有 findByCharId 方法 |

---

## Task 1: 修复大纲读取路径

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java:58-62`

- [ ] **Step 1: 修复 projectInfo.script.outline 读取路径**

```java
// 修复前（第 58-62 行）
Map<String, Object> projectInfo = project.getProjectInfo();
String visualStyle = (String) projectInfo.getOrDefault("visualStyle", "ANIME");
int targetDuration = getIntFromMap(projectInfo, "episodeDuration", 60);
String outline = (String) projectInfo.getOrDefault("scriptOutline", "");
String charactersDesc = getCharacterDescriptions(projectId);

// 修复后
Map<String, Object> projectInfo = project.getProjectInfo();
String visualStyle = (String) projectInfo.getOrDefault("visualStyle", "ANIME");
int targetDuration = getIntFromMap(projectInfo, "episodeDuration", 60);
// 正确读取 projectInfo.script.outline
@SuppressWarnings("unchecked")
Map<String, Object> scriptMap = (Map<String, Object>) projectInfo.get("script");
String outline = scriptMap != null ? (String) scriptMap.getOrDefault("outline", "") : "";
String charactersDesc = getCharacterDescriptions(projectId);
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -DskipTests 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java
git commit -m "fix: 修复 StoryboardService 读取大纲路径错误 (scriptOutline → script.outline)"
```

---

## Task 2: 实现角色描述获取方法

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java:152-155`

- [ ] **Step 1: 添加 CharacterRepository 依赖**

在第 44 行附近添加：
```java
@Resource
private CharacterRepository characterRepository;
```

- [ ] **Step 2: 实现 getCharacterDescriptions 方法**

替换第 152-155 行的 TODO：
```java
private String getCharacterDescriptions(String projectId) {
    List<Character> characters = characterRepository.findByProjectId(projectId);
    if (characters == null || characters.isEmpty()) {
        log.warn("项目没有配置角色: projectId={}", projectId);
        return "";
    }

    StringBuilder sb = new StringBuilder();
    for (Character c : characters) {
        Map<String, Object> info = c.getCharacterInfo();
        if (info == null) continue;

        String name = (String) info.get("name");
        String appearance = (String) info.get("appearance");
        String personality = (String) info.get("personality");
        String role = (String) info.get("role");

        if (name == null || name.isEmpty()) continue;

        sb.append("【").append(name).append("】");
        if (role != null && !role.isEmpty()) {
            sb.append(" 角色：").append(role);
        }
        if (appearance != null && !appearance.isEmpty()) {
            sb.append(" 外貌：").append(appearance);
        }
        if (personality != null && !personality.isEmpty()) {
            sb.append(" 性格：").append(personality);
        }
        sb.append("\n");
    }

    String result = sb.toString();
    log.info("获取角色描述: projectId={}, characters={}, descLength={}",
        projectId, characters.size(), result.length());
    return result;
}
```

- [ ] **Step 3: 添加必要的 import**

在文件头部添加：
```java
import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
```

- [ ] **Step 4: 编译验证**

Run: `cd /d/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -DskipTests 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java
git commit -m "feat: 实现 getCharacterDescriptions 从数据库获取角色设定"
```

---

## Task 3: 限制分集剧本生成集数

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java:64-67`
- Modify: `backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java:218-238`

- [ ] **Step 1: 修改 StoryboardService 传递 totalEpisodes**

修改第 64-67 行：
```java
// 修复前
List<Map<String, Object>> scripts = deepSeekTextService.generateEpisodeScript(
    outline, charactersDesc, targetDuration, visualStyle);

// 修复后
int totalEpisodes = getIntFromMap(projectInfo, "totalEpisodes", 1);
List<Map<String, Object>> scripts = deepSeekTextService.generateEpisodeScript(
    outline, charactersDesc, targetDuration, visualStyle, totalEpisodes);
```

- [ ] **Step 2: 修改 DeepSeekTextService 方法签名和 prompt**

修改第 218-238 行：
```java
// 修复前
public List<Map<String, Object>> generateEpisodeScript(
        String outlineNode, String characters, int durationSeconds, String visualStyle) {
    String systemPrompt = "你是一位专业的影视编剧。请根据提供的大纲和角色信息，生成结构化分集剧本。\n"
        + "输出格式为纯 JSON 数组，不要包含 markdown 代码块标记。\n"
        + "每个元素包含以下字段：\n"
        + "- title: 集标题\n"
        + "- content: 剧本正文内容（约" + (durationSeconds / 60) + "分钟对应的字数，中文约200-250字/分钟）\n"
        + "- characters: 本集出场角色，逗号分隔\n"
        + "- keyItems: 本集关键道具/场景，逗号分隔\n"
        + "- continuityNote: 连贯性备注\n"
        + "注意：内容要紧凑，适合" + durationSeconds + "秒的短视频。";

    String userPrompt = "大纲节点：" + outlineNode + "\n"
        + "角色：" + characters + "\n"
        + "视觉风格：" + visualStyle + "\n"
        + "目标时长：" + durationSeconds + "秒\n"
        + "请生成结构化分集剧本 JSON。";

// 修复后
public List<Map<String, Object>> generateEpisodeScript(
        String outlineNode, String characters, int durationSeconds, String visualStyle, int totalEpisodes) {
    String systemPrompt = "你是一位专业的影视编剧。请根据提供的大纲和角色信息，生成结构化分集剧本。\n"
        + "输出格式为纯 JSON 数组，不要包含 markdown 代码块标记。\n"
        + "每个元素包含以下字段：\n"
        + "- title: 集标题\n"
        + "- content: 剧本正文内容（约" + (durationSeconds / 60) + "分钟对应的字数，中文约200-250字/分钟）\n"
        + "- characters: 本集出场角色，逗号分隔\n"
        + "- keyItems: 本集关键道具/场景，逗号分隔\n"
        + "- continuityNote: 连贯性备注\n"
        + "注意：内容要紧凑，适合" + durationSeconds + "秒的短视频。\n"
        + "**重要约束**：必须生成恰好 " + totalEpisodes + " 集剧本，不要多也不要少！";

    String userPrompt = "大纲节点：" + outlineNode + "\n"
        + "角色：" + characters + "\n"
        + "视觉风格：" + visualStyle + "\n"
        + "目标时长：" + durationSeconds + "秒\n"
        + "需要生成的集数：" + totalEpisodes + " 集\n"
        + "请生成恰好 " + totalEpisodes + " 集结构化分集剧本 JSON。";
```

- [ ] **Step 3: 编译验证**

Run: `cd /d/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -DskipTests 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java \
       backend/com/comic/src/main/java/com/comic/ai/text/DeepSeekTextService.java
git commit -m "fix: 限制分集剧本生成为用户指定的集数 (totalEpisodes)"
```

---

## Task 4: 分镜中注入角色 ID（方案 A）

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java`

- [ ] **Step 1: 在 StoryboardService 添加角色 ID 映射方法**

在类末尾添加私有方法：
```java
/**
 * 构建角色名到 charId 的映射
 */
private Map<String, String> buildCharacterIdMap(String projectId) {
    List<Character> characters = characterRepository.findByProjectId(projectId);
    Map<String, String> nameToId = new HashMap<>();
    for (Character c : characters) {
        Map<String, Object> info = c.getCharacterInfo();
        if (info != null) {
            String name = (String) info.get("name");
            String charId = (String) info.get("charId");
            if (name != null && charId != null) {
                nameToId.put(name.trim(), charId);
            }
        }
    }
    log.info("构建角色ID映射: projectId={}, mapping={}", projectId, nameToId);
    return nameToId;
}

/**
 * 为分镜中的角色注入 charId
 */
private void injectCharacterIds(List<Map<String, Object>> shots, Map<String, String> nameToId) {
    for (Map<String, Object> shot : shots) {
        @SuppressWarnings("unchecked")
        List<String> charNames = (List<String>) shot.get("characters");
        if (charNames == null || charNames.isEmpty()) continue;

        List<Map<String, String>> charRefs = new ArrayList<>();
        for (String charName : charNames) {
            String charId = nameToId.get(charName.trim());
            Map<String, String> ref = new HashMap<>();
            ref.put("name", charName);
            if (charId != null) {
                ref.put("charId", charId);
                log.debug("角色注入成功: name={}, charId={}", charName, charId);
            } else {
                log.warn("角色未找到匹配: name={}", charName);
            }
            charRefs.add(ref);
        }
        shot.put("characterRefs", charRefs);
    }
}
```

- [ ] **Step 2: 在生成分镜后调用注入方法**

修改 `generateEpisodeScriptAndStoryboard` 方法中，第 76-79 行之后：
```java
// 原代码
List<Map<String, Object>> shots = deepSeekTextService.generateStoryboard(
    content, characters, targetDuration, visualStyle);
log.info("[Pipeline] Step2完成: 生成 {} 个分镜, projectId={}, episode={}", shots.size(), projectId, title);

// 新增：注入角色ID
Map<String, String> nameToId = buildCharacterIdMap(projectId);
injectCharacterIds(shots, nameToId);
```

- [ ] **Step 3: 修改 GridImageService 优先用 charId 匹配**

修改 `getCharacterReferenceUrls` 方法（约第 342-410 行）：
```java
private List<String> getCharacterReferenceUrls(Long episodeId) {
    List<String> urls = new ArrayList<>();
    try {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) {
            log.warn("角色参考图: episodeId={} 不存在", episodeId);
            return urls;
        }

        Map<String, Object> episodeInfo = episode.getEpisodeInfo();
        if (episodeInfo == null) {
            log.warn("角色参考图: episodeId={} episodeInfo 为空", episodeId);
            return urls;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) episodeInfo.get("shots");
        if (shots == null) {
            log.warn("角色参考图: episodeId={} shots 为空", episodeId);
            return urls;
        }

        // 收集所有 charId（优先）和角色名（兜底）
        java.util.Set<String> charIds = new java.util.LinkedHashSet<>();
        java.util.Set<String> charNames = new java.util.LinkedHashSet<>();

        for (Map<String, Object> shot : shots) {
            // 优先从 characterRefs 获取 charId
            @SuppressWarnings("unchecked")
            List<Map<String, String>> charRefs = (List<Map<String, String>>) shot.get("characterRefs");
            if (charRefs != null) {
                for (Map<String, String> ref : charRefs) {
                    String charId = ref.get("charId");
                    if (charId != null && !charId.isEmpty()) {
                        charIds.add(charId);
                    }
                    String name = ref.get("name");
                    if (name != null && !name.trim().isEmpty()) {
                        charNames.add(name.trim());
                    }
                }
            } else {
                // 兜底：从旧字段 characters 获取
                @SuppressWarnings("unchecked")
                List<String> characters = (List<String>) shot.get("characters");
                if (characters != null) {
                    for (String c : characters) {
                        if (c != null && !c.trim().isEmpty()) {
                            charNames.add(c.trim());
                        }
                    }
                }
            }
        }

        log.info("角色参考图: episodeId={} 收集到 charIds={}, charNames={}", episodeId, charIds, charNames);

        // 优先用 charId 匹配
        for (String charId : charIds) {
            Character ch = characterRepository.findByCharId(charId);
            if (ch == null) {
                log.warn("角色参考图: episodeId={} charId '{}' 未找到", episodeId, charId);
                continue;
            }
            String url = getCharacterImageUrl(ch);
            if (url != null && !url.isEmpty()) {
                urls.add(url);
                log.info("角色参考图: episodeId={} charId={} 匹配成功, url={}", episodeId, charId, url);
            }
        }

        // 兜底：用角色名匹配未找到的角色
        if (charNames.size() > charIds.size()) {
            List<Character> allChars = characterRepository.findByProjectId(episode.getProjectId());
            Map<String, Character> nameToChar = new HashMap<>();
            for (Character ch : allChars) {
                Map<String, Object> info = ch.getCharacterInfo();
                if (info != null) {
                    String name = (String) info.get(CharacterInfoKeys.NAME);
                    if (name != null) nameToChar.put(name.trim(), ch);
                }
            }

            for (String charName : charNames) {
                if (charIds.contains(charName)) continue; // 已处理
                Character ch = nameToChar.get(charName);
                if (ch == null) {
                    log.warn("角色参考图: episodeId={} 角色名 '{}' 未在数据库中找到匹配", episodeId, charName);
                    continue;
                }
                String url = getCharacterImageUrl(ch);
                if (url != null && !url.isEmpty()) {
                    urls.add(url);
                    log.info("角色参考图: episodeId={} 角色名 '{}' 匹配成功", episodeId, charName);
                }
            }
        }

        log.info("角色参考图: episodeId={} 最终URL数量={}", episodeId, urls.size());
    } catch (Exception e) {
        log.error("获取角色参考图失败: episodeId={}", episodeId, e);
    }
    return urls;
}

/**
 * 获取角色的参考图 URL（三视图或表情图）
 */
private String getCharacterImageUrl(Character ch) {
    Map<String, Object> info = ch.getCharacterInfo();
    if (info == null) return null;

    String url = (String) info.get(CharacterInfoKeys.THREE_VIEW_GRID_URL);
    if (url == null || url.isEmpty()) {
        url = (String) info.get(CharacterInfoKeys.EXPRESSION_GRID_URL);
    }
    return url;
}
```

- [ ] **Step 4: 编译验证**

Run: `cd /d/wiset/Wiset_Aivideo_Genetator/backend/com/comic && mvn compile -DskipTests 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java \
       backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "feat: 分镜中注入角色ID，GridImageService优先用charId匹配角色参考图"
```

---

## Task 5: 集成测试与验证

- [ ] **Step 1: 重启后端服务**

```bash
# 在IDE或终端重启 Spring Boot 应用
```

- [ ] **Step 2: 创建新项目测试**
1. 创建新项目，设置 totalEpisodes=1
2. 完成创意输入
3. 确认只生成 1 集剧本

- [ ] **Step 3: 检查日志确认角色描述传递**

Expected log output:
```
获取角色描述: projectId=xxx, characters=2, descLength=150
构建角色ID映射: projectId=xxx, mapping={张三=CHAR-001, 李四=CHAR-002}
角色注入成功: name=张三, charId=CHAR-001
角色参考图: episodeId=18 收集到 charIds=[CHAR-001], charNames=[张三]
角色参考图: episodeId=18 charId CHAR-001 匹配成功
```

- [ ] **Step 4: Final Commit (if all tests pass)**

```bash
git add -A
git commit -m "fix: 修复视频生成数据链 - 大纲读取、角色描述、集数限制、角色ID注入"
```

---

## Summary

| Task | Description | Files Modified |
|------|-------------|----------------|
| 1 | 修复大纲读取路径 | StoryboardService.java |
| 2 | 实现角色描述获取 | StoryboardService.java |
| 3 | 限制分集剧本集数 | StoryboardService.java, DeepSeekTextService.java |
| 4 | 注入角色ID | StoryboardService.java, GridImageService.java |
| 5 | 集成测试 | - |
