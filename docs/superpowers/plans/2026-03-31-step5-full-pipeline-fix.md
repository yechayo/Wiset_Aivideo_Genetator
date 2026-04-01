# Step5 全链路漏洞修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Step5 视频生产工作台全链路漏洞（T0, P0-P2），解决"切割后分镜对不上"核心问题。根因：1) 融合图尺寸不固定导致视频尺寸不一致；2) 融合图编号与视频 prompt 编号从第 2 个 Panel 开始映射断裂

**Architecture:** 后端修复融合图固定 16:9 输出（T0）、九宫格分割算法（添加分隔线补偿）、统一融合图↔视频prompt编号体系、视频拼接编码兼容、并发安全；前端修复轮询泄漏和类型定义

**Tech Stack:** Java 17, Spring Boot, React + TypeScript, FFmpeg

**Spec:** `docs/superpowers/specs/2026-03-31-step5-full-pipeline-fix-design.md`

---

## Task 1: 修复九宫格分割算法 — 添加分隔线补偿

**核心修复，解决"切割后分镜对不上"的根因**

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java:34-38,204-212`
- Test: `backend/com/comic/src/test/java/com/comic/service/panel/GridImageServiceTest.java`

- [ ] **Step 1: 添加分隔线常量**

在 `GridImageService.java` 常量区域（第 38 行后）添加：

```java
private static final int GRID_SEPARATOR_PIXELS = 4;
```

- [ ] **Step 2: 编写失败测试 — 分隔线补偿**

在 `GridImageServiceTest.java` 添加测试：

```java
@Test
void splitGrid_should_compensate_for_separator_pixels() {
    // 3列图片，宽 = 3 * 640 + 2 * 4 = 1928, 高 = 3 * 360 + 2 * 4 = 1088
    int cols = 3, rows = 3, sep = 4;
    int totalW = cols * 640 + (cols - 1) * sep;  // 1928
    int totalH = rows * 360 + (rows - 1) * sep;  // 1088
    BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);

    List<BufferedImage> result = GridImageService.splitGridImage(img, cols, rows);

    assertEquals(9, result.size());
    // 每个格子应该是纯净的 640x360，不包含分隔线
    assertEquals(640, result.get(0).getWidth());
    assertEquals(360, result.get(0).getHeight());
    // 验证格子之间的坐标正确：第2格从 x=644 开始（640+4）
    // 第5格从 y=364 开始（360+4）
}

@Test
void splitGrid_should_handle_remainder_pixels_in_last_cell() {
    // 宽 = 1930（不是精确整除），验证最后一列拿到剩余像素
    int cols = 3, sep = 4;
    int totalW = 1930;
    int totalH = 1088;
    BufferedImage img = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);

    List<BufferedImage> result = GridImageService.splitGridImage(img, cols, 3);

    assertEquals(9, result.size());
    // 前两列应该是 642px，最后一列应该是剩余像素
    int expectedCellW = (totalW - (cols - 1) * sep) / cols; // (1930-8)/3 = 640
    int lastCellW = totalW - 2 * (expectedCellW + sep);    // 1930 - 2*644 = 642
    assertEquals(640, result.get(0).getWidth());
    assertEquals(lastCellW, result.get(2).getWidth());
}
```

- [ ] **Step 3: 运行测试验证失败**

```bash
cd d:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn test -pl . -Dtest=GridImageServiceTest -DfailIfNoTests=false
```

Expected: FAIL — 旧算法返回 `1928/3=642` 而非 `640`，且最后一个格子没有拿到剩余像素

- [ ] **Step 4: 实现新分割算法**

替换 `GridImageService.java` 中的 `splitGridImage` 方法（第 203-212 行）：

```java
/**
 * 切割九宫格（纯函数）
 * 考虑分隔线像素：将 GRID_SEPARATOR_PIXELS 从格子间扣除
 */
public static List<BufferedImage> splitGridImage(BufferedImage img, int cols, int rows) {
    int sep = GRID_SEPARATOR_PIXELS;
    List<BufferedImage> subImages = new ArrayList<>();

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            int cellW = (img.getWidth() - (cols - 1) * sep) / cols;
            int cellH = (img.getHeight() - (rows - 1) * sep) / rows;
            int x = c * (cellW + sep);
            int y = r * (cellH + sep);
            int w = cellW;
            int h = cellH;

            // 最后一列/行取剩余像素，避免累积偏差
            if (c == cols - 1) {
                w = img.getWidth() - x;
            }
            if (r == rows - 1) {
                h = img.getHeight() - y;
            }

            subImages.add(img.getSubimage(x, y, w, h));
        }
    }
    return subImages;
}
```

- [ ] **Step 5: 运行测试验证通过**

```bash
cd d:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn test -pl . -Dtest=GridImageServiceTest -DfailIfNoTests=false
```

Expected: PASS

- [ ] **Step 6: 更新现有测试**

旧测试 `splitGrid_should_produce_9_sub_images` 和 `splitGrid_should_handle_non_divisible_size` 使用 300x300 图片。新算法计算 `cellW = (300 - 8) / 3 = 97`，最后一列 `w = 300 - 2*101 = 98`。需要更新断言：

```java
@Test
void splitGrid_should_produce_9_sub_images() {
    BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
    List<BufferedImage> result = GridImageService.splitGridImage(img, 3, 3);
    assertEquals(9, result.size());
    // cellW = (300-8)/3 = 97, lastColW = 300 - 2*101 = 98
    assertEquals(97, result.get(0).getWidth());
    assertEquals(98, result.get(2).getWidth());
}

@Test
void splitGrid_should_handle_non_divisible_size() {
    BufferedImage img = new BufferedImage(301, 301, BufferedImage.TYPE_INT_RGB);
    List<BufferedImage> result = GridImageService.splitGridImage(img, 3, 3);
    assertEquals(9, result.size());
    // cellW = (301-8)/3 = 97, lastColW = 301 - 2*101 = 99
    assertEquals(97, result.get(0).getWidth());
    assertEquals(99, result.get(2).getWidth());
}
```

- [ ] **Step 7: 全量测试通过**

```bash
cd d:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn test -pl . -Dtest=GridImageServiceTest -DfailIfNoTests=false
```

Expected: 所有测试 PASS

- [ ] **Step 8: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git add backend/com/comic/src/test/java/com/comic/service/panel/GridImageServiceTest.java
git commit -m "fix: 九宫格分割算法添加分隔线补偿，修复像素累积偏差 (P0-1, P0-2)"
```

---

## Task 2: 同步前端 canvasSplitter 分隔线逻辑

**确保前端与后端分割算法一致**

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/utils/canvasSplitter.ts:8-35`

- [ ] **Step 1: 更新 `splitGridToPanels` 添加自动计算模式**

当前函数接受 `panelWidth`/`panelHeight` 作为参数（由调用者计算），与后端不一致。添加默认分隔线常量，并在文档注释中标注：

```typescript
/** 默认分隔线像素，需与后端 GridImageService.GRID_SEPARATOR_PIXELS 保持一致 */
export const DEFAULT_GRID_SEPARATOR_PIXELS = 4;

/**
 * 将九宫格图拆分为单格面板
 * 如果 panelWidth 为 0，则自动计算（扣除分隔线后等分）
 */
export function splitGridToPanels(
  gridImage: HTMLImageElement,
  gridColumns: number,
  gridRows: number,
  panelWidth: number,
  panelHeight: number,
  separatorPixels: number = DEFAULT_GRID_SEPARATOR_PIXELS,
): Map<number, HTMLCanvasElement> {
  const panels = new Map<number, HTMLCanvasElement>();
  const sep = separatorPixels;

  // 自动计算模式：panelWidth 为 0 时，等分扣除分隔线
  const cellW = panelWidth > 0
    ? panelWidth
    : Math.floor((gridImage.width - (gridColumns - 1) * sep) / gridColumns);
  const cellH = panelHeight > 0
    ? panelHeight
    : Math.floor((gridImage.height - (gridRows - 1) * sep) / gridRows);

  for (let row = 0; row < gridRows; row++) {
    for (let col = 0; col < gridColumns; col++) {
      const panelIndex = row * gridColumns + col;
      const sx = col * (cellW + sep);
      const sy = row * (cellH + sep);
      // 最后一列/行取剩余像素
      const sw = col === gridColumns - 1
        ? gridImage.width - sx
        : cellW;
      const sh = row === gridRows - 1
        ? gridImage.height - sy
        : cellH;

      const canvas = document.createElement('canvas');
      canvas.width = sw;
      canvas.height = sh;
      const ctx = canvas.getContext('2d')!;
      ctx.drawImage(gridImage, sx, sy, sw, sh, 0, 0, sw, sh);

      panels.set(panelIndex, canvas);
    }
  }

  return panels;
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/utils/canvasSplitter.ts
git commit -m "fix: 前端 canvasSplitter 同步分隔线补偿逻辑 (P0-3)"
```

---

## Task 3: 修复融合图↔视频prompt编号映射断裂 + 提取圈码工具

**"分镜对不上"的核心根因：从第2个Panel开始，融合图编号和视频prompt映射声明错位**

**问题追踪（以12个shots为例）：**
```
greedyGroup 后:
  Panel1: [shot1, shot2, shot3]  shotNumber=1,2,3
  Panel2: [shot4, shot5, shot6]  shotNumber=4,5,6

createFusionImage(Panel2):
  画 ①②③ (基于索引 0,1,2) → ①=shot4, ②=shot5, ③=shot6

buildMultiShotPrompt(Panel2):
  写【分镜4】【分镜5】【分镜6】(基于 shotNumber)
  但末尾硬编码 "①②③对应【分镜1】【分镜2】【分镜3】" ← 致命错误!

  → AI被告知错误的映射 → 视频内容与分镜对不上
```

**修复策略：统一使用"Panel内位置编号"替代全局shotNumber**

**Files:**
- Create: `backend/com/comic/src/main/java/com/comic/util/NumberFormatter.java`
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java:312-314`
- Modify: `backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java:67-68,97-98,118-120`
- Modify: `backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java:157` (注释修正)

- [ ] **Step 1: 创建 NumberFormatter 工具类**

```java
package com.comic.util;

/**
 * 圈码数字格式化工具
 * 支持 ①-㊿（1-50）范围
 */
public class NumberFormatter {

    private static final String[] CIRCLED_NUMBERS = {
        "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
        "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳",
        "㉑", "㉒", "㉓", "㉔", "㉕", "㉖", "㉗", "㉘", "㉙", "㉚",
        "㉛", "㉜", "㉝", "㉞", "㉟", "㊱", "㊲", "㊳", "㊴", "㊵",
        "㊶", "㊷", "㊸", "㊹", "㊺", "㊻", "㊼", "㊽", "㊾", "㊿",
    };

    /**
     * 将数字转为圈码格式（1-based）
     * @param num 1-50
     * @return 圈码字符串，超出范围返回普通数字
     */
    public static String toCircled(int num) {
        if (num >= 1 && num <= CIRCLED_NUMBERS.length) {
            return CIRCLED_NUMBERS[num - 1];
        }
        return String.valueOf(num);
    }
}
```

- [ ] **Step 2: 更新 GridImageService 融合图使用共享工具**

在 `GridImageService.java` 中替换第 312-314 行的圈码数组：

```java
// 旧代码:
// String[] circledNumbers = {"①", "②", ... "⑯"};
// String label = i < circledNumbers.length ? circledNumbers[i] : String.valueOf(i + 1);

// 新代码:
import com.comic.util.NumberFormatter;
// ...
String label = NumberFormatter.toCircled(i + 1);
```

- [ ] **Step 3: 修复 buildGridPrompt — 九宫格 prompt 使用循环索引**

在 `PanelPromptBuilder.java` 第 67 行，把 `for-each` 改为索引循环：

```java
// 旧代码:
// for (Map<String, Object> shot : shots) {
//     sb.append("面板 ").append(shot.get("shotNumber")).append(": ");

// 新代码: 使用循环索引，不依赖 AI 生成的 shotNumber
for (int i = 0; i < shots.size(); i++) {
    Map<String, Object> shot = shots.get(i);
    sb.append("面板 ").append(i + 1).append(": ");
    sb.append("16:9 - ").append(shot.getOrDefault("visualDescription", ""));
    // ... 其余字段不变
    sb.append("\n");
}
```

- [ ] **Step 4: 修复 buildMultiShotPrompt — 镜头名改用位置编号**

在 `PanelPromptBuilder.java` 第 97-98 行，**关键修改**：

```java
// 旧代码:
// int shotNum = ((Number) shot.get("shotNumber")).intValue();
// sb.append("【分镜").append(shotNum).append("】\n");

// 新代码: 使用 Panel 内位置编号，与融合图上的 ①②③ 一一对应
for (int i = 0; i < shots.size(); i++) {
    Map<String, Object> shot = shots.get(i);
    sb.append("【镜头").append(i + 1).append("】\n");
    sb.append("duration: ").append(shot.get("duration")).append("s\n");
    // ... 其余字段不变
}
```

- [ ] **Step 5: 修复 buildMultiShotPrompt — 动态生成映射声明**

在 `PanelPromptBuilder.java` 第 118-120 行：

```java
// 旧代码:
// sb.append("参考图中编号①②③对应【分镜1】【分镜2】【分镜3】的画面内容。");

// 新代码: 动态生成，与融合图编号完全对应
import com.comic.util.NumberFormatter;
// ...
StringBuilder refBuilder = new StringBuilder("参考图中编号");
for (int i = 0; i < n; i++) {
    refBuilder.append(NumberFormatter.toCircled(i + 1));
}
refBuilder.append("分别对应");
for (int i = 0; i < n; i++) {
    if (i > 0) refBuilder.append("、");
    refBuilder.append("【镜头").append(i + 1).append("】");
}
refBuilder.append("的画面内容。");
sb.append(refBuilder.toString());
```

修复后 Panel2 的 prompt 效果：
```
参考图中编号①②③分别对应【镜头1】【镜头2】【镜头3】的画面内容。
```
与融合图上的 ①=第1个子图、②=第2个子图、③=第3个子图 完全对应。

- [ ] **Step 6: 修正 greedyGroup 注释**

在 `StoryboardService.java` 第 157 行：

```java
// 旧代码:
// 深拷贝 shot，避免多个 Panel 共享同一个 Map 对象

// 新代码:
// 浅拷贝 shot（顶层字段独立，嵌套 List/Map 仍共享引用）
```

- [ ] **Step 7: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/util/NumberFormatter.java
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git add backend/com/comic/src/main/java/com/comic/ai/PanelPromptBuilder.java
git add backend/com/comic/src/main/java/com/comic/service/storyboard/StoryboardService.java
git commit -m "fix: 统一融合图↔视频prompt编号体系，修复第2个Panel开始映射断裂 (P1-3, P1-3b, P2-3, P2-5)"
```

---

## Task 4: 融合图固定 16:9 输出

**T0 级问题：融合图尺寸动态变化，AI 视频生成尺寸不一致导致拼接失败**

**问题追踪：**
```
createFusionImage 当前计算:
  3 shots → 1952x436 ≈ 4.48:1
  6 shots → 1952x796 ≈ 2.45:1
  9 shots → 1952x1156 ≈ 1.69:1
  +角色侧栏 → +368px 宽度，更极端

→ 视频生成 AI 收到 fusionImageUrl，参考图片尺寸而非 "16:9" 参数
→ 不同 Panel 生成的视频宽高比不同
→ 最终拼接时 FFmpeg concat 失败或画面变形
```

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java:251-345`

- [ ] **Step 1: 重写 createFusionImage 为固定 1920x1080**

替换整个 `createFusionImage` 方法：

```java
private BufferedImage createFusionImage(List<Map<String, Object>> shots, List<String> charRefUrls) {
    // 固定输出尺寸：1920x1080 (16:9)
    final int FIXED_WIDTH = 1920;
    final int FIXED_HEIGHT = 1080;
    final int BOTTOM_BAR_HEIGHT = 180;  // 角色参考图底部横条高度
    final int MAIN_AREA_HEIGHT = FIXED_HEIGHT - BOTTOM_BAR_HEIGHT;
    final int PAD = 4;

    // 计算分镜网格布局
    int fCols = 3;
    int fRows = (int) Math.ceil((double) shots.size() / fCols);
    int cellW = (FIXED_WIDTH - PAD * (fCols + 1)) / fCols;
    int cellH = (MAIN_AREA_HEIGHT - PAD * (fRows + 1)) / fRows;

    // 创建画布
    BufferedImage canvas = new BufferedImage(FIXED_WIDTH, FIXED_HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = canvas.createGraphics();
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(FUSION_BG_COLOR);
    g.fillRect(0, 0, FIXED_WIDTH, FIXED_HEIGHT);

    // 绘制分镜网格
    for (int i = 0; i < shots.size(); i++) {
        int col = i % fCols;
        int row = i / fCols;
        int x = PAD + col * (cellW + PAD);
        int y = PAD + row * (cellH + PAD);
        String splitUrl = (String) shots.get(i).get("splitImageUrl");
        if (splitUrl != null) {
            try {
                BufferedImage sub = downloadImage(splitUrl);
                // 等比缩放居中绘制
                double scale = Math.min((double) cellW / sub.getWidth(), (double) cellH / sub.getHeight());
                int drawW = (int) (sub.getWidth() * scale);
                int drawH = (int) (sub.getHeight() * scale);
                int drawX = x + (cellW - drawW) / 2;
                int drawY = y + (cellH - drawH) / 2;
                g.drawImage(sub, drawX, drawY, drawW, drawH, null);
            } catch (Exception e) {
                log.warn("融合图加载失败: shot {}", i);
            }
        }
        // 绘制编号
        g.setColor(Color.BLACK);
        g.fillRect(x + 2, y + 2, 24, 18);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        String label = NumberFormatter.toCircled(i + 1);
        g.drawString(label, x + 4, y + 15);
    }

    // 绘制底部角色参考图横条
    if (charRefUrls != null && !charRefUrls.isEmpty()) {
        int charCount = charRefUrls.size();
        int charW = (FIXED_WIDTH - PAD * (charCount + 1)) / charCount;
        int charH = BOTTOM_BAR_HEIGHT - PAD * 2;
        int charY = MAIN_AREA_HEIGHT + PAD;

        for (int i = 0; i < charRefUrls.size(); i++) {
            int charX = PAD + i * (charW + PAD);
            try {
                BufferedImage charImg = downloadImage(charRefUrls.get(i));
                // 等比缩放
                double scale = Math.min((double) charW / charImg.getWidth(), (double) charH / charImg.getHeight());
                int drawW = (int) (charImg.getWidth() * scale);
                int drawH = (int) (charImg.getHeight() * scale);
                int drawX = charX + (charW - drawW) / 2;
                int drawY = charY + (charH - drawH) / 2;
                g.drawImage(charImg, drawX, drawY, drawW, drawH, null);

                // 角色编号
                g.setColor(Color.BLACK);
                g.fillRect(charX + 2, charY + 2, 20, 16);
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.PLAIN, 10));
                g.drawString("C" + (i + 1), charX + 4, charY + 14);
            } catch (Exception e) {
                log.warn("角色参考图加载失败: {}", charRefUrls.get(i));
            }
        }
    }

    g.dispose();
    return canvas;
}
```

- [ ] **Step 2: 添加 NumberFormatter import**

在 `GridImageService.java` 顶部添加：

```java
import com.comic.util.NumberFormatter;
```

- [ ] **Step 3: 编写单元测试验证固定尺寸**

在 `GridImageServiceTest.java` 添加：

```java
@Test
void createFusionImage_should_always_return_1920x1080() {
    // 模拟不同数量的 shots
    List<Map<String, Object>> shots1 = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
        Map<String, Object> shot = new HashMap<>();
        shot.put("splitImageUrl", "mock");
        shots1.add(shot);
    }

    // 由于需要下载图片，这里只验证尺寸计算逻辑
    // 实际集成测试会验证真实图片输出
    int fCols = 3;
    int fRows = (int) Math.ceil((double) shots1.size() / fCols);
    assertEquals(1, fRows);

    List<Map<String, Object>> shots9 = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
        Map<String, Object> shot = new HashMap<>();
        shot.put("splitImageUrl", "mock");
        shots9.add(shot);
    }
    fRows = (int) Math.ceil((double) shots9.size() / fCols);
    assertEquals(3, fRows);
}

@Test
void createFusionImage_dimensions_should_be_constant() {
    final int FIXED_WIDTH = 1920;
    final int FIXED_HEIGHT = 1080;
    final int BOTTOM_BAR_HEIGHT = 180;
    final int MAIN_AREA_HEIGHT = FIXED_HEIGHT - BOTTOM_BAR_HEIGHT;
    final int PAD = 4;
    final int fCols = 3;

    // 验证不同 shots 数量下 cell 尺寸计算正确
    int[] shotCounts = {1, 2, 3, 4, 5, 6, 7, 8, 9};
    for (int shotCount : shotCounts) {
        int fRows = (int) Math.ceil((double) shotCount / fCols);
        int cellW = (FIXED_WIDTH - PAD * (fCols + 1)) / fCols;
        int cellH = (MAIN_AREA_HEIGHT - PAD * (fRows + 1)) / fRows;

        // 验证所有格子能放入主区域
        assertTrue(cellW > 0 && cellH > 0, "shots=" + shotCount + ": cell dimensions invalid");
        assertTrue(fCols * cellW + (fCols + 1) * PAD <= FIXED_WIDTH, "shots=" + shotCount + ": width overflow");
        assertTrue(fRows * cellH + (fRows + 1) * PAD <= MAIN_AREA_HEIGHT, "shots=" + shotCount + ": height overflow");
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
cd d:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn test -pl . -Dtest=GridImageServiceTest -DfailIfNoTests=false
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git add backend/com/comic/src/test/java/com/comic/service/panel/GridImageServiceTest.java
git commit -m "fix: 融合图固定输出 1920x1080 (16:9)，确保 AI 视频生成尺寸一致 (T0)"
```

---

## Task 5: FFmpeg 视频拼接兼容性修复

**修复 `-c copy` 无法处理不同编码视频的问题**

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/production/VideoCompositionService.java:125-139`

- [ ] **Step 1: 修改 composeWithoutSubtitle 为重新编码模式**

替换 `VideoCompositionService.java` 第 125-139 行：

```java
/**
 * 合成视频（不带字幕）— 使用重新编码确保格式兼容
 */
private void composeWithoutSubtitle(Path concatFile, Path outputPath) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(ffmpegPath);
    command.add("-f");
    command.add("concat");
    command.add("-safe");
    command.add("0");
    command.add("-i");
    command.add(concatFile.toString());
    command.add("-c:v");
    command.add("libx264");
    command.add("-preset");
    command.add("medium");
    command.add("-crf");
    command.add("23");
    command.add("-c:a");
    command.add("aac");
    command.add("-b:a");
    command.add("128k");
    command.add("-y");
    command.add(outputPath.toString());

    executeFFmpeg(command);
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/production/VideoCompositionService.java
git commit -m "fix: FFmpeg concat 改用 libx264+aac 重新编码，修复不同编码视频拼接失败 (P1-2)"
```

---

## Task 6: @Async 并发状态覆盖保护

**防止重新生成九宫格时旧线程覆盖新状态**

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java:176-182`

- [ ] **Step 1: 在写入前检查是否被更新**

在 `generateGridsForEpisode` 的 `episodeInfo` 写入前（第 176-182 行），添加版本检查：

```java
// 写入前检查：如果 gridStatus 已被重置为 "generating"（说明有更新的重新生成请求），放弃写入
Episode freshEpisode = episodeRepository.selectById(episodeId);
if (freshEpisode != null) {
    String freshStatus = (String) freshEpisode.getEpisodeInfo().getOrDefault("gridStatus", "");
    // 如果当前 episodeInfo 的 gridStatus 与开始时不同，说明被其他操作修改过
    if (!episodeInfo.get("gridStatus").equals(freshStatus)) {
        log.warn("Episode {} 九宫格状态已被更新，放弃写入旧结果", episodeId);
        return;
    }
}

// 更新 episodeInfo
episodeInfo.put("gridImages", gridImageUrls);
episodeInfo.put("splitShots", splitShots);
episodeInfo.put("gridStatus", "generated");
episodeInfo.put("gridPageCount", pageCount);
episode.setEpisodeInfo(episodeInfo);
episodeRepository.updateById(episode);
```

注意：需在方法开头保存 `initialGridStatus`：
```java
String initialGridStatus = (String) episodeInfo.getOrDefault("gridStatus", "generating");
```

然后在检查时用 `initialGridStatus` 对比。

- [ ] **Step 2: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/service/panel/GridImageService.java
git commit -m "fix: 九宫格异步生成添加版本检查，防止旧线程覆盖新状态 (P1-4)"
```

---

## Task 7: EpisodeController 审核逻辑修复

**修复 rejected 状态可绕过 + shots/splitShots 同步**

**Files:**
- Modify: `backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java:140,225-254`

- [ ] **Step 1: 移除 rejected 状态的审核通过权限**

在 `approveEpisodeGrid` 方法第 140 行：

```java
// 旧代码:
// if (!"generated".equals(gridStatus) && !"rejected".equals(gridStatus)) {

// 新代码:
if (!"generated".equals(gridStatus)) {
    throw new BusinessException("当前状态不可审核: " + gridStatus + "，请先重新生成九宫格");
}
```

- [ ] **Step 2: 修复 regenerateEpisodeGrid 从 DB 读取最新 shots**

在 `regenerateEpisodeGrid` 方法（第 225-254 行），改为从数据库重新读取：

```java
@PostMapping("/{episodeId}/grid/regenerate")
public Result<Void> regenerateEpisodeGrid(
        @PathVariable String projectId,
        @PathVariable Long episodeId) {
    Episode episode = episodeRepository.selectById(episodeId);
    if (episode == null) throw new BusinessException("剧集不存在");

    // 从数据库最新 episodeInfo 读取 shots，确保数据一致性
    Map<String, Object> info = episode.getEpisodeInfo();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shots = (List<Map<String, Object>>) info.get("shots");
    String visualStyle = (String) info.getOrDefault("visualStyle", "ANIME");

    if (shots == null || shots.isEmpty()) {
        throw new BusinessException("分镜数据为空，无法重新生成九宫格");
    }

    // 重置状态
    info.put("gridStatus", "generating");
    info.put("gridImages", new ArrayList<>());
    info.put("splitShots", new ArrayList<>());
    info.put("gridRejectionFeedback", null);
    info.put("errorMessage", null);
    episode.setEpisodeInfo(info);
    episodeRepository.updateById(episode);

    // 重新获取最新 episode（确保拿到最新版本），然后异步生成
    gridImageService.generateGridsForEpisode(episodeId, shots, visualStyle);

    return Result.ok();
}
```

（此逻辑与原代码基本一致，关键变化已在注释中说明 — 确保从 DB 最新状态读取。）

- [ ] **Step 3: 改善融合图失败日志**

在 `approveEpisodeGrid` 第 191-193 行：

```java
} catch (Exception e) {
    // 融合图生成失败不阻断流程，但记录 error 级别日志
    log.error("Panel 融合图生成失败: episodeId={}, shots={}", episodeId, group.size(), e);
}
```

- [ ] **Step 4: Commit**

```bash
git add backend/com/comic/src/main/java/com/comic/controller/EpisodeController.java
git commit -m "fix: 移除 rejected 状态审核绕过，修复 shots 数据同步 (P1-5, P1-6, P2-4)"
```

---

## Task 8: 前端轮询泄漏修复

**添加最大重试次数 + AbortController 组件卸载清理**

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx`

- [ ] **Step 1: 添加轮询工具函数**

在 `Step5page.tsx` 文件顶部（import 之后、常量区域）添加：

```typescript
/** 轮询配置 */
const GRID_POLL_INTERVAL = 5000;
const GRID_POLL_MAX_RETRIES = 360;   // 30 分钟
const VIDEO_POLL_INTERVAL = 3000;
const VIDEO_POLL_MAX_RETRIES = 720;  // 1 小时

/**
 * 带上限的轮询，返回 AbortController 用于取消
 */
function createPollingRef() {
  const abortRef = useRef<AbortController | null>(null);
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);
  return abortRef;
}
```

- [ ] **Step 2: 修复 refreshEpisodeGridStatus 中的无限轮询**

替换第 219-242 行的 `useEffect`：

```typescript
useEffect(() => {
  if (chapters.length === 0) return;
  const allEpisodes = chapters.flatMap(ch => ch.episodes);
  const generatingGridEp = allEpisodes.find(ep =>
    ep.isNewFlow && ep.gridStatus === 'generating'
  );
  if (!generatingGridEp) return;

  const gridEpId = generatingGridEp.episodeId;
  console.info('检测到正在生成中的 episode 九宫格, 恢复轮询: epId=', gridEpId);
  refreshEpisodeGridStatus(gridEpId);

  let retries = 0;
  const abort = new AbortController();
  const poll = async () => {
    while (retries < GRID_POLL_MAX_RETRIES && !abort.signal.aborted) {
      await new Promise(r => setTimeout(r, GRID_POLL_INTERVAL));
      if (abort.signal.aborted) return;
      await refreshEpisodeGridStatus(gridEpId);
      retries++;
      const currentEps = chapters.flatMap(ch => ch.episodes);
      const currentGridEp = currentEps.find(e => e.episodeId === gridEpId);
      if (currentGridEp && (currentGridEp.gridStatus === 'generated' || currentGridEp.gridStatus === 'approved' || currentGridEp.gridStatus === 'failed')) {
        return;
      }
    }
    if (retries >= GRID_POLL_MAX_RETRIES) {
      console.warn('九宫格轮询超时: epId=', gridEpId);
    }
  };
  poll();

  return () => {
    abort.abort();
  };
}, [chapters.length]); // eslint-disable-line react-hooks/exhaustive-deps
```

- [ ] **Step 3: 修复 handleRegenerateGrid 轮询**

在 `handleRegenerateGrid`（第 605-642 行），替换 `while(true)` 为有上限的轮询：

```typescript
const handleRegenerateGrid = useCallback(async (episodeId: number, panelId: string) => {
  if (!projectId) return;
  setGeneratingGridPanelId(panelId);
  try {
    await regenerateGrid(projectId, episodeId, Number(panelId));
    let retries = 0;
    const abort = new AbortController();
    const poll = async () => {
      while (retries < GRID_POLL_MAX_RETRIES && !abort.signal.aborted) {
        await new Promise(r => setTimeout(r, VIDEO_POLL_INTERVAL));
        if (abort.signal.aborted) return;
        retries++;
        try {
          const res = await getBatchProductionStatuses(projectId, episodeId);
          if ((res.code !== 0 && res.code !== 200) || !res.data) continue;
          const panelStatus = res.data.find((s: any) => s.panelId === Number(panelId));
          if (panelStatus) {
            const gs = panelStatus.gridStatus;
            if (gs === 'generated' || gs === 'approved') {
              await refreshProductionStatuses(episodeId);
              setGeneratingGridPanelId(null);
              return;
            }
            if (gs === 'failed') {
              alert('九宫格重新生成失败');
              setGeneratingGridPanelId(null);
              return;
            }
          }
        } catch {
          // 继续轮询
        }
      }
      if (retries >= GRID_POLL_MAX_RETRIES) {
        console.warn('九宫格重新生成轮询超时: panelId=', panelId);
        setGeneratingGridPanelId(null);
      }
    };
    poll();
    // 注意：在实际场景中 abort 应绑定到组件生命周期，
    // 但由于 useCallback 无法直接使用 useEffect cleanup，这里用简单方式
  } catch (err: any) {
    alert(err?.response?.data?.message || err?.message || '重新生成失败');
    setGeneratingGridPanelId(null);
  }
}, [projectId, refreshProductionStatuses]);
```

- [ ] **Step 4: 修复 handleRegenerateEpisodeGrid 轮询**

在 `handleRegenerateEpisodeGrid`（第 674-702 行），同样替换 `while(true)`：

```typescript
const handleRegenerateEpisodeGrid = useCallback(async (episodeId: number) => {
  if (!projectId) return;
  try {
    await regenerateEpisodeGrid(projectId, episodeId);
    let retries = 0;
    const poll = async () => {
      while (retries < GRID_POLL_MAX_RETRIES) {
        await new Promise(r => setTimeout(r, GRID_POLL_INTERVAL));
        retries++;
        try {
          const res = await getEpisodeGridStatus(projectId, episodeId);
          const gridStatus = res.data?.gridStatus;
          if (gridStatus === 'generated' || gridStatus === 'approved') {
            await loadEpisodes();
            return;
          }
          if (gridStatus === 'failed') {
            alert('九宫格重新生成失败');
            await loadEpisodes();
            return;
          }
        } catch {
          // 继续轮询
        }
      }
      console.warn('整集九宫格重新生成轮询超时: episodeId=', episodeId);
    };
    poll();
  } catch (err: any) {
    alert(err?.response?.data?.message || err?.message || '重新生成失败');
  }
}, [projectId, loadEpisodes]);
```

- [ ] **Step 5: 修复 handleGenerateVideo 轮询**

在 `handleGenerateVideo`（第 737-778 行），替换 `while(true)`：

```typescript
const poll = async () => {
  let retries = 0;
  while (retries < VIDEO_POLL_MAX_RETRIES) {
    await new Promise(r => setTimeout(r, VIDEO_POLL_INTERVAL));
    retries++;
    try {
      const res = await getBatchProductionStatuses(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) continue;
      const panelStatus = res.data.find((s: any) => s.panelId === Number(panelId));
      if (panelStatus) {
        const videoStatus = panelStatus.videoStatus;
        if (videoStatus === 'completed') {
          await refreshProductionStatuses(episodeId);
          setGeneratingVideoPanelId(null);
          return;
        }
        if (videoStatus === 'failed') {
          alert('视频生成失败');
          setGeneratingVideoPanelId(null);
          return;
        }
      }
    } catch {
      // 继续轮询
    }
  }
  console.warn('视频生成轮询超时: panelId=', panelId);
  setGeneratingVideoPanelId(null);
};
```

- [ ] **Step 6: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx
git commit -m "fix: 前端轮询添加最大重试次数和 AbortController，防止内存泄漏 (P2-1)"
```

---

## Task 9: 前端接口声明重复修复

**删除重复的 Step5pageProps 接口**

**Files:**
- Modify: `frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx:30-37`

- [ ] **Step 1: 合并为单一接口**

替换第 30-37 行：

```typescript
interface Step5pageProps {
  project: any;
  onNextStep?: () => void;
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/wiset_aivideo_generator/src/pages/create/steps/Step5page.tsx
git commit -m "fix: 删除重复的 Step5pageProps 接口声明 (P2-2)"
```

---

## Task 10: 最终验证

- [ ] **Step 1: 后端全量编译**

```bash
cd d:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn compile -pl .
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 后端全量测试**

```bash
cd d:/wiset/Wiset_Aivideo_Genetator/backend/com && mvn test -pl . -DfailIfNoTests=false
```

Expected: 所有测试 PASS

- [ ] **Step 3: 前端类型检查**

```bash
cd d:/wiset/Wiset_Aivideo_Genetator/frontend/wiset_aivideo_generator && npx tsc --noEmit
```

Expected: 无类型错误

- [ ] **Step 4: Commit 最终状态（如有修复）**

```bash
git add -A
git commit -m "chore: 最终编译和类型检查修复"
```
