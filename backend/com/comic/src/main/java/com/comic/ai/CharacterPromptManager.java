package com.comic.ai;

import com.comic.entity.Character;
import org.springframework.stereotype.Component;

/**
 * 角色图片生成提示词管理器
 * 从 promptManager.md 迁移的专业提示词模板
 */
@Component
public class CharacterPromptManager {

    /**
     * 视觉风格枚举
     */
    public enum VisualStyle {
        D_3D("3D"),
        REAL("REAL"),
        ANIME("ANIME");

        private final String code;

        VisualStyle(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /**
     * 构建九宫格表情大全图提示词
     * 一次性生成包含9个表情的3x3网格图
     */
    public String buildExpressionGridPrompt(Character character, VisualStyle style) {
        String appearance = character.getAppearance() != null ? character.getAppearance() : "";
        String personality = character.getPersonality() != null ? character.getPersonality() : "";

        switch (style) {
            case D_3D:
                return build3DExpressionPrompt(appearance, personality);
            case REAL:
                return buildREALExpressionPrompt(appearance, personality);
            case ANIME:
                return buildANIMEExpressionPrompt(appearance, personality);
            default:
                return build3DExpressionPrompt(appearance, personality);
        }
    }

    /**
     * 构建三视图大全图提示词
     * 一次性生成包含3个角度的三视图
     */
    public String buildThreeViewGridPrompt(Character character, VisualStyle style) {
        String appearance = character.getAppearance() != null ? character.getAppearance() : "";
        String personality = character.getPersonality() != null ? character.getPersonality() : "";

        switch (style) {
            case D_3D:
                return build3DThreeViewPrompt(appearance, personality);
            case REAL:
                return buildREALThreeViewPrompt(appearance, personality);
            case ANIME:
                return buildANIMEThreeViewPrompt(appearance, personality);
            default:
                return build3DThreeViewPrompt(appearance, personality);
        }
    }

    // ================= 3D风格提示词 =================

    /**
     * 3D风格九宫格表情提示词
     */
    private String build3DExpressionPrompt(String appearance, String personality) {
        return "3D动漫风格，风格化3D渲染，PBR材质渲染，高精度3D建模。\n\n" +
               "构图：特写肖像，仅头部和肩部，专注于面部表情。\n\n" +
               "角色面部表情参考表，3×3网格布局，展示9种不同的面部表情\n" +
               "（喜悦、愤怒、悲伤、惊讶、恐惧、厌恶、中性、思考、疲惫）。\n\n" +
               "角色描述：\n" +
               "外貌：" + appearance + "\n" +
               "性格：" + personality + "\n\n" +
               "关键约束：\n" +
               "- 仅特写肖像镜头（头部和肩部）\n" +
               "- 无全身、无下半身、无腿部\n" +
               "- 专注于面部特征、表情和头部\n" +
               "- 纯色平背景 - 仅纯色背景\n" +
               "- 所有9个表情中保持一致的角色设计\n" +
               "- 3×3网格构图，每个表情清晰可辨\n" +
               "- 无文字、无水印、无标签、无签名\n\n" +
               "负面提示词：nsfw, text, watermark, label, signature, full body, legs, lower body, " +
               "arms, hands, weapons, accessories, complex background, outdoor, scenery, " +
               "low quality, blurry, distorted, ugly, deformed";
    }

    /**
     * 3D风格三视图提示词
     */
    private String build3DThreeViewPrompt(String appearance, String personality) {
        return "3D动漫风格，风格化3D渲染，PBR材质渲染，高精度3D建模。\n\n" +
               "角色三视图参考表，展示三个角度：正面、侧面、背面。\n\n" +
               "角色描述：\n" +
               "外貌：" + appearance + "\n" +
               "性格：" + personality + "\n\n" +
               "构图要求：\n" +
               "- 三个视图水平排列或垂直排列\n" +
               "- 正面视图：展示完整的正面设计\n" +
               "- 侧面视图：展示侧面轮廓\n" +
               "- 背面视图：展示背部设计\n" +
               "- 全身视图，从头部到脚部\n" +
               "- 所有视图中保持一致的角色设计\n" +
               "- 纯色平背景\n" +
               "- 无文字、无水印、无标签\n\n" +
               "负面提示词：nsfw, text, watermark, label, signature, portrait, close-up, " +
               "head only, upper body only, complex background, outdoor, scenery, " +
               "low quality, blurry, distorted, ugly, deformed";
    }

    // ================= REAL风格提示词 =================

    /**
     * REAL风格九宫格表情提示词
     */
    private String buildREALExpressionPrompt(String appearance, String personality) {
        return "真人写实风格，高度真实的人物肖像摄影。\n\n" +
               "构图：特写肖像，仅头部和肩部，专注于面部表情。\n\n" +
               "角色面部表情参考表，3×3网格布局，展示9种不同的面部表情\n" +
               "（喜悦、愤怒、悲伤、惊讶、恐惧、厌恶、中性、思考、疲惫）。\n\n" +
               "角色描述：\n" +
               "外貌：" + appearance + "\n" +
               "性格：" + personality + "\n\n" +
               "关键约束：\n" +
               "- 仅特写肖像镜头（头部和肩部）\n" +
               "- 真实的人脸特征和皮肤质感\n" +
               "- 专注于面部表情\n" +
               "- 纯色平背景\n" +
               "- 所有9个表情中保持一致的角色外貌\n" +
               "- 3×3网格构图，每个表情清晰可辨\n" +
               "- 无文字、无水印、无标签、无签名\n" +
               "- 专业摄影棚灯光\n" +
               "- 高分辨率，8K质量\n\n" +
               "负面提示词：nsfw, text, watermark, label, signature, full body, legs, lower body, " +
               "arms, hands, weapons, accessories, complex background, outdoor, scenery, " +
               "cartoon, anime, 3d render, low quality, blurry, distorted, ugly, deformed";
    }

    /**
     * REAL风格三视图提示词
     */
    private String buildREALThreeViewPrompt(String appearance, String personality) {
        return "真人写实风格，高度真实的人物摄影。\n\n" +
               "角色三视图参考表，展示三个角度：正面、侧面、背面。\n\n" +
               "角色描述：\n" +
               "外貌：" + appearance + "\n" +
               "性格：" + personality + "\n\n" +
               "构图要求：\n" +
               "- 三个视图水平排列或垂直排列\n" +
               "- 正面视图：展示完整的正面设计\n" +
               "- 侧面视图：展示侧面轮廓\n" +
               "- 背面视图：展示背部设计\n" +
               "- 全身视图，从头部到脚部\n" +
               "- 所有视图中保持一致的角色外貌\n" +
               "- 纯色平背景\n" +
               "- 专业摄影棚灯光\n" +
               "- 高分辨率，8K质量\n" +
               "- 无文字、无水印、无标签\n\n" +
               "负面提示词：nsfw, text, watermark, label, signature, portrait, close-up, " +
               "head only, upper body only, complex background, outdoor, scenery, " +
               "cartoon, anime, 3d render, low quality, blurry, distorted, ugly, deformed";
    }

    // ================= ANIME风格提示词 =================

    /**
     * ANIME风格九宫格表情提示词
     */
    private String buildANIMEExpressionPrompt(String appearance, String personality) {
        return "2D动漫风格，精美的动漫角色设计。\n\n" +
               "构图：特写肖像，仅头部和肩部，专注于面部表情。\n\n" +
               "角色面部表情参考表，3×3网格布局，展示9种不同的面部表情\n" +
               "（喜悦、愤怒、悲伤、惊讶、恐惧、厌恶、中性、思考、疲惫）。\n\n" +
               "角色描述：\n" +
               "外貌：" + appearance + "\n" +
               "性格：" + personality + "\n\n" +
               "关键约束：\n" +
               "- 仅特写肖像镜头（头部和肩部）\n" +
               "- 经典的动漫角色设计风格\n" +
               "- 专注于面部表情\n" +
               "- 纯色平背景\n" +
               "- 所有9个表情中保持一致的角色设计\n" +
               "- 3×3网格构图，每个表情清晰可辨\n" +
               "- 无文字、无水印、无标签、无签名\n" +
               "- 高质量动漫艺术\n\n" +
               "负面提示词：nsfw, text, watermark, label, signature, full body, legs, lower body, " +
               "arms, hands, weapons, accessories, complex background, outdoor, scenery, " +
               "realistic, 3d render, low quality, blurry, distorted, ugly, deformed";
    }

    /**
     * ANIME风格三视图提示词
     */
    private String buildANIMEThreeViewPrompt(String appearance, String personality) {
        return "2D动漫风格，精美的动漫角色设计。\n\n" +
               "角色三视图参考表，展示三个角度：正面、侧面、背面。\n\n" +
               "角色描述：\n" +
               "外貌：" + appearance + "\n" +
               "性格：" + personality + "\n\n" +
               "构图要求：\n" +
               "- 三个视图水平排列或垂直排列\n" +
               "- 正面视图：展示完整的正面设计\n" +
               "- 侧面视图：展示侧面轮廓\n" +
               "- 背面视图：展示背部设计\n" +
               "- 全身视图，从头部到脚部\n" +
               "- 所有视图中保持一致的角色设计\n" +
               "- 纯色平背景\n" +
               "- 经典的动漫角色设计风格\n" +
               "- 高质量动漫艺术\n" +
               "- 无文字、无水印、无标签\n\n" +
               "负面提示词：nsfw, text, watermark, label, signature, portrait, close-up, " +
               "head only, upper body only, complex background, outdoor, scenery, " +
               "realistic, 3d render, low quality, blurry, distorted, ugly, deformed";
    }
}
