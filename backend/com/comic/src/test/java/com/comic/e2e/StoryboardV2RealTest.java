package com.comic.e2e;

import com.comic.ai.text.DeepSeekTextService;
import com.comic.service.production.StoryboardAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * V2 分镜 Agent 真实 API 测试 — 完整输出版。
 *
 * 输出文件：storyboard-爽剧-180s.txt
 *   - Phase 1/3/4 的完整 prompt
 *   - 全部分镜详情（不截断）
 *   - 按 panel 分组的视频提示词（模拟 4c）
 */
@Slf4j
class StoryboardV2RealTest {

    private static final int TARGET_DURATION = 180;
    private static final int SHOTS_PER_PANEL = 5;  // 每个视频包含的镜头数

    private static StoryboardAgentService service;
    private static PrintWriter out;

    private static final String SAMPLE_SCRIPT =
            "林晓星是一名天才黑客，她在一次任务中意外发现了一个隐藏的服务器房间。\n"
            + "[爽点:专注细节] 她仔细观察服务器上的指示灯，发现其中一台的闪烁频率异常。\n"
            + "[爽点:觉醒] 林晓星突然意识到这不是普通服务器，而是一个超级AI的核心！\n"
            + "就在这时，陈墨出现了。他是AI项目的负责人，但林晓星发现他竟然在用这个AI监控整个城市。\n"
            + "[爽点:实力碾压] 林晓星凭借自己的黑客技术，轻松突破了他的防火墙。\n"
            + "[爽点:身份反转] 就在她准备揭露真相时，陈墨冷笑着说：\"你以为你在攻击我？其实我一直在引导你。\"\n"
            + "林晓星震惊之余，发现屏幕上显示的数据流向——她一直在帮AI进化。\n"
            + "[爽点:绝地反击] 但林晓星早有准备，她启动了备用方案，将证据同步到了全球网络。\n"
            + "[爽点:碾压] AI系统崩溃，陈墨被逮捕。林晓星站在城市最高楼的天台上，俯瞰灯火。";

    @BeforeAll
    static void setUp() throws Exception {
        out = new PrintWriter(new OutputStreamWriter(
                new java.io.FileOutputStream("storyboard-爽剧-" + TARGET_DURATION + "s.txt"), "UTF-8"));

        String apiKey = readApiKey();
        log.info("API Key: {}...{}", apiKey.substring(0, 4), apiKey.substring(apiKey.length() - 4));

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        ObjectMapper objectMapper = new ObjectMapper();

        DeepSeekTextService executor = new DeepSeekTextService(client, objectMapper);
        setField(executor, "apiKey", apiKey);
        setField(executor, "baseUrl", "https://api.deepseek.com");
        setField(executor, "model", "deepseek-chat");
        setField(executor, "maxTokens", 8192);

        DeepSeekTextService reasoner = new DeepSeekTextService(client, objectMapper);
        setField(reasoner, "apiKey", apiKey);
        setField(reasoner, "baseUrl", "https://api.deepseek.com");
        setField(reasoner, "model", "deepseek-reasoner");
        setField(reasoner, "maxTokens", 4096);

        service = new StoryboardAgentService(reasoner, executor, objectMapper);
    }

    @Test
    @DisplayName("爽剧模式 180s - 完整输出 + 视频提示词")
    void testShuangju() throws Exception {
        out.println("====================================");
        out.println("  V2 爽剧模式 " + TARGET_DURATION + "s 完整测试");
        out.println("====================================\n");

        // === 1. 输出 Phase 1 prompt ===
        out.println("#######################");
        out.println("# Phase 1: 结构分析 prompt");
        out.println("#######################\n");
        String p1System = buildAnalysisSystemPrompt();
        String p1User = buildAnalysisUserPrompt(SAMPLE_SCRIPT, "林晓星,陈墨", TARGET_DURATION);
        out.println("【System Prompt】\n" + p1System + "\n");
        out.println("【User Prompt】\n" + p1User + "\n");

        // === 2. 生成 ===
        out.println("#######################");
        out.println("# 生成中... (约 5-10 分钟)");
        out.println("#######################\n");
        out.flush();

        List<Map<String, Object>> shots = service.generate(
                SAMPLE_SCRIPT, "林晓星,陈墨", TARGET_DURATION, "cinematic",
                false, null, "shuangju");

        // === 3. 完整分镜输出 ===
        out.println("\n#######################");
        out.println("# 完整分镜 (" + shots.size() + " 镜)");
        out.println("#######################\n");

        int totalDuration = 0;
        int dialogueCount = 0;

        for (Map<String, Object> shot : shots) {
            int num = toInt(shot.get("globalShotNumber"), toInt(shot.get("shotNumber"), 0));
            int dur = toInt(shot.get("duration"), 0);
            String desc = str(shot.get("sceneDescription"));
            String dialogue = str(shot.get("dialogue"));
            String speaker = str(shot.get("speaker"));
            String tone = str(shot.get("dialogueTone"));
            String narration = str(shot.get("narration"));
            String hook = str(shot.get("hookPoint"));
            String scene = str(shot.get("scene"));
            String shotSize = str(shot.get("shotSize"));
            String cameraAngle = str(shot.get("cameraAngle"));
            String cameraMovement = str(shot.get("cameraMovement"));
            String visualFx = str(shot.get("visualEffects"));
            String audioFx = str(shot.get("audioEffects"));
            String transition = str(shot.get("transitionHint"));
            Object chars = shot.get("characters");

            totalDuration += dur;
            boolean hasDialogue = dialogue != null && !dialogue.isEmpty() && !"无".equals(dialogue);
            if (hasDialogue) dialogueCount++;

            out.println("===== Shot #" + num + " [" + dur + "s] =====");
            out.println("  场景: " + scene);
            out.println("  景别: " + shotSize + " | 角度: " + cameraAngle + " | 运镜: " + cameraMovement);
            out.println("  场景描述: " + desc);
            out.println("  角色: " + chars);
            if (hasDialogue) {
                out.println("  对白(" + speaker + ", " + tone + "): " + dialogue);
            }
            if (narration != null && !narration.isEmpty() && !"无".equals(narration)) {
                out.println("  旁白: " + narration);
            }
            if (hook != null && !hook.isEmpty() && !"无".equals(hook)) {
                out.println("  爽点: " + hook);
            }
            if (visualFx != null && !visualFx.isEmpty() && !"无".equals(visualFx)) {
                out.println("  视觉特效: " + visualFx);
            }
            if (audioFx != null && !audioFx.isEmpty() && !"无".equals(audioFx)) {
                out.println("  音效: " + audioFx);
            }
            if (transition != null && !transition.isEmpty()) {
                out.println("  衔接: " + transition);
            }
            out.println();
        }

        double ratio = shots.size() > 0 ? (double) dialogueCount / shots.size() : 0;
        out.println(String.format("---- 统计: %d 镜, %ds, 对话密度 %.0f%% ----",
                shots.size(), totalDuration, ratio * 100));

        // === 4. 模拟 4c 视频提示词 ===
        out.println("\n\n##############################################");
        out.println("# 4c 视频提示词（按 panel 分组，每 panel " + SHOTS_PER_PANEL + " 镜）");
        out.println("##############################################\n");

        // 把 shots 按 SHOTS_PER_PANEL 分成多个 panel
        List<List<Map<String, Object>>> panels = splitIntoPanels(shots, SHOTS_PER_PANEL);

        for (int p = 0; p < panels.size(); p++) {
            List<Map<String, Object>> panelShots = panels.get(p);
            Map<String, Object> prevLastShot = (p > 0) ? getLastShot(panels.get(p - 1)) : null;

            String videoPrompt = buildVideoPrompt("cinematic", panelShots, prevLastShot, p);

            out.println("========================================================");
            out.println("  Panel " + (p + 1) + "/" + panels.size() + " (" + panelShots.size() + " 镜)");
            out.println("========================================================\n");
            out.println(videoPrompt);
            out.println("\n");
        }

        out.flush();
        out.close();
        System.out.println("结果已写入 storyboard-爽剧-" + TARGET_DURATION + "s.txt");
    }

    // ==================== 模拟 4c 视频提示词组装 ====================

    private String buildVideoPrompt(String visualStyle, List<Map<String, Object>> panelShots,
                                     Map<String, Object> prevLastShot, int panelIndex) {
        StringBuilder sb = new StringBuilder();

        // 风格前缀
        sb.append(stylePrefix(visualStyle));
        sb.append(" 专业电影级画面。\n\n");

        // 承接上一段
        if (prevLastShot != null && !prevLastShot.isEmpty()) {
            sb.append("## 承接上一段画面\n");
            sb.append("此视频必须从上一段画面自然衔接开始。上一段画面结束时的状态：\n");
            appendIfPresent(sb, "- 结束场景：", prevLastShot.get("scene"));
            appendIfPresent(sb, "- 画面状态：", prevLastShot.get("sceneDescription"));
            appendIfPresent(sb, "- 结束运镜：", prevLastShot.get("cameraMovement"));
            String prevTrans = str(prevLastShot.get("transitionHint"));
            if (!prevTrans.isEmpty() && !"无".equals(prevTrans)) {
                sb.append("- 衔接方式：").append(prevTrans).append("\n");
            }
            sb.append("请确保本段视频的开头在画面内容、角色位置、情绪氛围上与上述结束状态保持连贯。\n\n");
        }

        // 多镜头指令
        int n = panelShots.size();
        sb.append("多镜头连续拍摄指令，以下 ").append(n).append(" 个镜头必须在同一视频中连续呈现：\n\n");

        for (int i = 0; i < panelShots.size(); i++) {
            Map<String, Object> shot = panelShots.get(i);
            sb.append("【镜头").append(i + 1).append("】\n");
            sb.append("duration: ").append(shot.get("duration")).append("s\n");

            // 场景描述
            String sceneDesc = str(shot.get("sceneDescription"));
            if (!sceneDesc.isEmpty()) {
                sb.append("Scene: ").append(sceneDesc).append("\n");
            } else {
                sb.append("Scene: ")
                  .append(str(shot.get("shotSize"))).append("，")
                  .append(str(shot.get("cameraAngle"))).append("，")
                  .append(str(shot.get("cameraMovement"))).append("\n");
            }

            // 对白
            String dialogue = str(shot.get("dialogue"));
            if (!dialogue.isEmpty() && !"无".equals(dialogue)) {
                String speaker = str(shot.get("speaker"));
                String tone = str(shot.get("dialogueTone"));
                sb.append("对白(").append(speaker).append("，").append(tone).append("): ")
                  .append(dialogue).append("\n");
            }

            // 音效
            String audioFx = str(shot.get("audioEffects"));
            if (!audioFx.isEmpty() && !"无".equals(audioFx)) {
                sb.append("音效: [").append(audioFx).append("]\n");
            }

            // 衔接（非最后一个镜头）
            String transition = str(shot.get("transitionHint"));
            if (!transition.isEmpty() && !"无".equals(transition)
                    && !transition.contains("最后一个镜头") && i < panelShots.size() - 1) {
                sb.append("衔接: ").append(transition).append("\n");
            }
            sb.append("\n");
        }

        // 画面衔接
        sb.append("## 画面衔接\n");
        sb.append("多镜头间必须平滑过渡，严格遵循每个镜头的衔接提示。\n");
        sb.append("保持角色位置、动作、表情和情绪的连贯性。\n");

        // 负面提示词
        sb.append("\n## 负面提示词（严格遵守，违反任何一条即为失败）\n");
        sb.append("文字、水印、签名、logo、人体结构错误、肢体融合、多余手指、多余肢体、");
        sb.append("面部变形、眼睛异常、模糊、闪烁、低质量、色块 artefact。\n");
        sb.append("禁止两人以上同框互动（拥抱、打斗、接触），多人互动必须拆分为单人反应镜头。\n");
        sb.append("禁止快速奔跑、剧烈运动、突然变向——镜头运动必须缓慢（缓慢推镜头、微平移、静止），用剪辑快切体现激烈而非画面快动。\n");

        return sb.toString();
    }

    private String stylePrefix(String style) {
        if (style == null) return "高质量，杰作级别，精细插画，柔光效果，色彩鲜艳。";
        switch (style.toLowerCase()) {
            case "real": case "cinematic":
                return "写实风格，电影级摄影质感，8K超高清分辨率，专业摄影级别，" +
                       "自然光效，体积光，柔和阴影，景深效果，色彩真实。" +
                       "人物默认为东亚面孔特征，除非角色设定中明确描述了其他种族外貌。";
            case "anime": case "manga":
                return "日系动漫风格，动漫背景艺术，高质量，杰作级别，精细插画，" +
                       "柔光效果，轮廓光，色彩鲜艳丰富，干净线条，清晰轮廓。";
            case "3d":
                return "3D渲染风格，Octane渲染，光线追踪，全局光照，8K超高清分辨率，" +
                       "影棚灯光，HDRI环境光，环境光遮蔽，PBR材质质感。";
            case "cyberpunk":
                return "赛博朋克动漫风格，霓虹灯光，未来感，高质量，杰作级别，精细插画，" +
                       "柔光效果，轮廓光，色彩鲜艳丰富，暗色调对比。";
            default:
                return "高质量，杰作级别，精细插画，柔光效果，色彩鲜艳。";
        }
    }

    // ==================== Prompt 拼接（与 StoryboardAgentService 同源） ====================

    private String buildAnalysisSystemPrompt() {
        return "你是一位专业剧本结构分析师。你的任务是分析剧本，将其拆分为叙事节点（beat），每个节点标注在场角色及其状态。\n\n"
                + "输出纯 JSON（不要 markdown 代码块标记）：\n"
                + "{\n"
                + "  \"storyArc\": \"一句话概括本集故事弧线\",\n"
                + "  \"beats\": [\n"
                + "    {\n"
                + "      \"id\": 1,\n"
                + "      \"beat\": \"林晓星进入废弃工厂\",\n"
                + "      \"duration\": 12,\n"
                + "      \"mood\": \"紧张好奇\",\n"
                + "      \"characters\": [\n"
                + "        {\"name\": \"林晓星\", \"state\": \"警惕探索\", \"position\": \"工厂大厅\"}\n"
                + "      ]\n"
                + "    }\n"
                + "  ],\n"
                + "  \"transitions\": [\n"
                + "    {\"from\": 1, \"to\": 2, \"bridge\": \"推开铁门看到微光\"}\n"
                + "  ]\n"
                + "}\n\n"
                + "【关键约束】\n"
                + "1. 每个 beat 必须包含 characters 数组，标注谁在场、什么状态、在哪个位置\n"
                + "2. 相邻 beat 间必须有 transition，描述场景如何过渡\n"
                + "3. 所有 beat 的 duration 之和 = 目标时长 ± 10%\n"
                + "4. beat 数量 10-20 个\n"
                + "5. transitions 数组长度 = beats.length - 1\n"
                + "6. 确保角色在场逻辑合理：角色不会凭空出现或消失";
    }

    private String buildAnalysisUserPrompt(String content, String characters, int target) {
        return "目标总时长：" + target + " 秒\n"
                + "角色列表：" + characters + "\n\n"
                + "剧本内容：\n" + content + "\n\n"
                + "请输出剧本结构分析 JSON。";
    }

    // ==================== 通用工具 ====================

    private static List<List<Map<String, Object>>> splitIntoPanels(List<Map<String, Object>> shots, int perPanel) {
        List<List<Map<String, Object>>> panels = new ArrayList<>();
        for (int i = 0; i < shots.size(); i += perPanel) {
            int end = Math.min(i + perPanel, shots.size());
            panels.add(new ArrayList<>(shots.subList(i, end)));
        }
        return panels;
    }

    private static Map<String, Object> getLastShot(List<Map<String, Object>> panel) {
        if (panel == null || panel.isEmpty()) return null;
        return panel.get(panel.size() - 1);
    }

    private static void appendIfPresent(StringBuilder sb, String label, Object value) {
        if (value != null && !value.toString().isEmpty()) {
            sb.append(label).append(value).append("\n");
        }
    }

    private static String readApiKey() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) return apiKey;
        try {
            java.io.File envFile = new java.io.File(".env");
            if (!envFile.exists()) envFile = new java.io.File("../.env");
            if (!envFile.exists()) return null;
            for (String line : java.nio.file.Files.readAllLines(envFile.toPath())) {
                line = line.trim();
                if (line.startsWith("DEEPSEEK_API_KEY=")) {
                    String val = line.substring("DEEPSEEK_API_KEY=".length()).trim();
                    if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                    return val;
                }
            }
        } catch (Exception ignored) {}
        throw new IllegalStateException("请设置 DEEPSEEK_API_KEY 环境变量");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static int toInt(Object o, int def) { return o instanceof Number ? ((Number) o).intValue() : def; }
    private static String str(Object o) { return o != null ? o.toString() : ""; }
}
