# 非人类角色物种支持

## 问题

角色参考图（三视图和表情九宫格）的 prompt 都是按人类角色写的。但故事中可能包含拟人化动物、奇幻/科幻种族、真实动物等非人类角色，系统没有角色物种概念，导致生成的参考图不正确。

## 设计

### 1. 数据模型

在现有的 `characterInfo` Map 中新增 `species` 字段（与 `appearance`、`personality` 等平级）。无需修改数据库表结构。

**`CharacterInfoKeys` 新增常量：**
```
SPECIES = "species"
```

**物种枚举值：**

| 值 | 含义 |
|---|---|
| `HUMAN` | 人类角色（默认） |
| `ANTHRO_ANIMAL` | 拟人化动物（猫娘、狐妖、龙族等，有人形身体但保留动物特征） |
| `CREATURE` | 奇幻/科幻种族（精灵、恶魔、机器人、外星人等） |
| `ANIMAL` | 真实动物（宠物、坐骑、无人类形态的灵兽等） |

**向后兼容：** `species` 为 null 或缺失时一律按 `HUMAN` 处理，旧数据无需迁移。

**校验：** 后端 `updateCharacter()` 和 `parseCharacters()` 需校验 species 值，无效值默认为 `HUMAN`。

### 2. AI 物种识别

**`CharacterExtractService` prompt 修改：**

在角色提取 prompt 中增加 `species` 字段要求，LLM 从角色描述中推断物种类型。

**Prompt 新增内容（中文，与现有 prompt 风格一致）：**

字段要求列表中新增：
```
species: 角色物种类型，必须为以下值之一：HUMAN（人类）、ANTHRO_ANIMAL（拟人化动物，有人形身体但保留动物特征）、CREATURE（奇幻/科幻种族，如精灵、机器人、恶魔）、ANIMAL（真实动物形态，如宠物、坐骑、灵兽）
```

输出格式示例中新增：
```json
{
  "name": "小黑",
  "species": "ANIMAL",
  "role": "配角",
  ...
}
```

**推断规则（供 LLM 参考）：**
- 动物特征 + 人形身体（猫耳、狐尾、龙鳞 + 人形）→ `ANTHRO_ANIMAL`
- 非人类种族（精灵、机器人、恶魔、外星人）→ `CREATURE`
- 纯动物形态，无人类特征（灵兽、宠物、坐骑）→ `ANIMAL`
- 其余 → `HUMAN`

**解析与持久化路径：**
- `parseCharacters()` — 从 LLM JSON 输出中读取 `species`，缺失或无效时默认 `HUMAN`
- `saveCharacters()` — 通过 `CharacterInfoKeys.SPECIES` 写入 `characterInfo` map

### 3. 用户确认（前端 Step3）

在 `Step3page.tsx` 的角色编辑卡片中新增物种下拉框。

**选项：**

| 值 | 显示标签 |
|---|---|
| HUMAN | 人类 |
| ANTHRO_ANIMAL | 拟人化动物 |
| CREATURE | 奇幻/科幻种族 |
| ANIMAL | 真实动物 |

默认值来自 AI 识别结果，用户可手动修改，修改后通过角色更新 API 保存。

**需要添加 `species` 的前端 TypeScript 接口：**
- `CharacterListItem` — Step3 角色列表使用
- `CharacterDraft` — 角色提取响应使用
- `CharacterStatus` — Step4 角色详情展示使用

### 4. CharacterPromptManager 适配

**三视图 prompt 适配：**

现有三视图 prompt 结构：
```
[风格前缀] + [任务指令] + [角色描述] + [构图要求] + [关键要求] + [负向提示词]
```

物种相关文本注入到 **[关键要求]** 部分（风格前缀之后、负向提示词之前）。所有新增内容使用中文。

现有硬编码短语 `正确的人体结构` 需按物种条件替换：
- `HUMAN` — 保持 `正确的人体结构`
- `ANTHRO_ANIMAL` — 替换为 `正确的拟人化身体结构，保留动物特征（耳朵、尾巴、爪等）`
- `CREATURE` — 替换为 `正确的种族身体结构，展现该种族特有的体型和特征`
- `ANIMAL` — 替换为 `自然的动物形态，四足或飞行姿态，无衣物配饰`

**表情九宫格 prompt 适配：**

同样注入到表情 prompt 的 **[关键要求]** 部分。

现有取景约束 `仅限特写肖像镜头（头部和肩部）` 需按物种调整：
- `HUMAN` — 保持 `仅限特写肖像镜头（头部和肩部）`
- `ANTHRO_ANIMAL` — `特写肖像镜头（头部和肩部），表情通过面部、耳朵、尾巴动态体现`
- `CREATURE` — `特写肖像镜头，表情通过该种族特征部位体现`
- `ANIMAL` — `特写镜头（头部），表情通过耳朵、眼睛、尾巴姿态体现，不要肩部以下`

**表情图生成跳过逻辑不变：** 无论物种，表情九宫格仅为主角和反派生成。

## 需修改的文件

### 后端
- `CharacterInfoKeys.java` — 新增 `SPECIES` 常量
- `CharacterDraftModel.java` — 新增 `species` 字段
- `CharacterExtractService.java` — 更新提取 prompt、`parseCharacters()`、`saveCharacters()`
- `CharacterUpdateRequest.java` — 新增 `species` 字段
- `CharacterListItemResponse.java` — 新增 `species` 字段及映射
- `CharacterStatusResponse.java` — 新增 `species` 字段及映射
- `CharacterPromptManager.java` — 根据物种适配三视图和表情图 prompt

### 前端
- `project.types.ts` — `CharacterListItem`、`CharacterDraft`、`CharacterStatus` 新增 `species`
- `Step3page.tsx` — 角色编辑卡片中新增物种下拉框

## 不在范围内
- 数据库迁移（不需要）
- 图像生成 API（Seedream）的修改
- 表情图生成跳过逻辑的修改
- 分镜/故事板生成 prompt 的修改（后续考虑）
