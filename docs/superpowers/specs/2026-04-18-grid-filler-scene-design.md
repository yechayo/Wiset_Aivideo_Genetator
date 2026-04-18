# 宫格图非满格填充优化设计

## 问题

当分镜数不能刚好填满方形网格时（如7个分镜用3×3），AI图像模型需要将剩余格子填充为纯黑色。但AI经常无法遵循这个指令，导致：
- 分隔线缺失或错位
- 空格位置仍被画上内容
- 超过50%的出错率

## 方案：占位内容填充

将多余格子的「纯黑填充」指令替换为无关紧要的通用过渡场景描述。AI画实际内容远比画黑格可靠，切割时直接丢弃多余格子。

## 改动范围

### 1. PanelPromptBuilder.java — 占位场景池 + pickFillerScene

新增预设场景池，按视觉风格分类（ANIME/REAL/3D等），每个风格6-8个通用无角色场景：
- 自然/风景类：夕阳天际线、云彩移动、树叶摇曳、雨落水面
- 环境/氛围类：空旷走廊、咖啡杯热气、窗外月光、远处街灯
- 抽象/过渡类：光线穿雾、水面涟漪、飞鸟剪影、飘散纸片

```java
private static final Map<String, List<String>> FILLER_POOL = Map.of(
    "DEFAULT", List.of(
        "远景 — 夕阳余晖洒在城市天际线上，无角色",
        "中景 — 天空中的云彩缓慢移动，光线柔和，无角色",
        ...
    ),
    "ANIME", List.of(...),
    "REAL", List.of(...)
);

private String pickFillerScene(String visualStyle, int index) {
    List<String> pool = FILLER_POOL.getOrDefault(visualStyle, FILLER_POOL.get("DEFAULT"));
    return pool.get(index % pool.size());
}
```

### 2. PanelPromptBuilder.java — buildGridPrompt 修改

将空格填充循环从「纯黑色填充，不绘制任何内容」改为调用 `pickFillerScene` 生成占位场景描述。

### 3. GridImageService.java — splitGridImage 修改

切割时增加 `actualCount` 参数，只切割前 N 个格子（实际分镜数），多余格子直接跳过不上传。

## 约束

- 图像模型只能生成16:9固定比例，网格形状只能是方形
- 占位场景无角色出现，与当前视觉风格一致
- 同一页内不重复选取占位场景
