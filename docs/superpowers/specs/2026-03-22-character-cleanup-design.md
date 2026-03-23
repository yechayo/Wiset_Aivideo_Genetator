# 角色管理模块代码清理

日期：2026-03-22

## 背景

角色管理系统（4.1-4.4）差距分析发现以下可清理项：
- 融合编辑器中角色参考图选择器包含三种来源（标准/三视图/表情），但需求 4.3 要求下游优先使用三视图
- `standardImageUrl` 字段在 Character 实体中存在但无任何生成逻辑
- `PromptBuilder.java` 中有未使用的英文 prompt 方法（当前流程使用 `CharacterPromptManager` 的中文模板）

## 决策

- 融合编辑器只使用三视图作为角色参考图来源
- 清理 `standardImageUrl` 死字段
- 删除未使用的英文 prompt 方法

## 不改动

- 表情图生成逻辑（`CharacterImageGenerationService`）保持不变，仍在角色管理页面生成和展示
- `expressionGridUrl` 在角色管理页面（Step4）的展示保持不变
- `CharacterPromptManager` 中文提示词保持不变

---

## 改动 1：融合编辑器只使用三视图

### fusionStore.ts

- 删除 `OverlaySourceType` 类型（`'standard' | 'threeView' | 'expression'`）
- 删除 `overlaySourceType` 状态字段
- 删除 `setOverlaySourceType` 方法
- `assignCharacterToPanel`：直接使用 `char.threeViewGridUrl`，fallback 到 `char.expressionGridUrl`
- `restoreFromProduction`：overlay 的 `sourceType` 和 imageUrl 逻辑简化为只用三视图

### PanelToolbar.tsx

- 删除图片源选择器（标准/三视图/表情三个按钮及相关逻辑）
- 删除 `overlaySourceType` 和 `setOverlaySourceType` 的引用

### CharacterPalette.tsx

- 只展示三视图图片，移除标准和表情图展示
- 删除 `standardImageUrl` 和 `expressionGridUrl` 的引用

---

## 改动 2：清理 standardImageUrl 死字段

### 后端

| 文件 | 操作 |
|------|------|
| `Character.java` | 删除 `standardImageUrl` 字段 |
| `CharacterStatusResponse.java` | 删除 `standardImageUrl` 字段 |
| `GridInfoResponse.java` | `CharacterReferenceInfo` 中删除 `standardImageUrl` |
| `EpisodeProductionService.java` | 移除 `info.setStandardImageUrl(c.getStandardImageUrl())` |
| `schema.sql` | 删除 `standard_image_url` 列定义 |

新增迁移脚本：

| 文件 | 内容 |
|------|------|
| `V11__remove_standard_image_url.sql` | `ALTER TABLE t_character DROP COLUMN standard_image_url;` |

### 前端

| 文件 | 操作 |
|------|------|
| `project.types.ts` | 删除 `standardImageUrl` 字段（CharacterDraft / CharacterStatus） |
| `episode.types.ts` | `CharacterReferenceInfo` 中删除 `standardImageUrl` |
| `Step4page.tsx` | 移除对 `standardImageUrl` 的引用 |

---

## 改动 3：清理未使用的英文 prompt 方法

### PromptBuilder.java

删除以下两个方法：
- `buildExpressionPrompt(Character character, String expressionType)` — 第 532-547 行
- `buildThreeViewPrompt(Character character, String viewType)` — 第 552-582 行

---

## 影响范围

- 后端：5 个 Java 文件 + 1 个 SQL 文件 + 1 个迁移脚本
- 前端：5 个 TypeScript/TSX 文件
- 数据库：1 个列删除（需迁移）
- 无 API 接口变更
- 无前端路由变更
