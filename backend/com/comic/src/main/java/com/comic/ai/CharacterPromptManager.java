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
        D_3D("3D", "3d-realistic"),
        REAL("REAL", "realistic"),
        ANIME("ANIME", "2d-anime"),
        MANGA("MANGA", "japanese-manga"),
        INK("INK", "ink-chinese"),
        CYBERPUNK("CYBERPUNK", "cyberpunk");

        private final String code;
        private final String frontendValue;

        VisualStyle(String code, String frontendValue) {
            this.code = code;
            this.frontendValue = frontendValue;
        }

        public String getCode() {
            return code;
        }

        public String getFrontendValue() {
            return frontendValue;
        }

        public static VisualStyle fromCode(String code) {
            if (code == null || code.isEmpty()) return D_3D;
            for (VisualStyle style : values()) {
                if (style.code.equalsIgnoreCase(code)) {
                    return style;
                }
            }
            // 不命中 code 则尝试前端值
            return fromFrontendValue(code);
        }

        public static VisualStyle fromFrontendValue(String value) {
            if (value == null || value.isEmpty()) return D_3D;
            for (VisualStyle s : values()) {
                if (s.frontendValue.equalsIgnoreCase(value)) return s;
            }
            return D_3D;
        }

        /** MANGA/INK/CYBERPUNK 复用 ANIME 提示词模板 */
        public VisualStyle getPromptStyle() {
            if (this == MANGA || this == INK || this == CYBERPUNK) return ANIME;
            return this;
        }

        /** 只有水墨风允许黑白 */
        public boolean isFullColor() {
            return this != INK;
        }
    }

    /**
     * 构建九宫格表情大全图提示词
     * 一次性生成包含9个表情的3x3网格图
     */
    public String buildExpressionGridPrompt(Character character, VisualStyle style) {
        String appearance = character.getAppearance() != null ? character.getAppearance() : "";
        String personality = character.getPersonality() != null ? character.getPersonality() : "";

        switch (style.getPromptStyle()) {
            case D_3D:
                return build3DExpressionPrompt(appearance, personality);
            case REAL:
                return buildREALExpressionPrompt(appearance, personality);
            case ANIME:
                return buildANIMEExpressionPrompt(appearance, personality, style);
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

        switch (style.getPromptStyle()) {
            case D_3D:
                return build3DThreeViewPrompt(appearance, personality);
            case REAL:
                return buildREALThreeViewPrompt(appearance, personality);
            case ANIME:
                return buildANIMEThreeViewPrompt(appearance, personality, style);
            default:
                return build3DThreeViewPrompt(appearance, personality);
        }
    }

    // ================= 3D风格提示词 =================

    /**
     * 3D风格九宫格表情提示词
     */
    private String build3DExpressionPrompt(String appearance, String personality) {
        return "full color illustration, NOT black and white, NOT grayscale, " +
               "Xianxia 3D animation character, semi-realistic style, Xianxia animation aesthetics, " +
               "high precision 3D modeling, PBR shading with soft translucency, subsurface scattering, " +
               "ambient occlusion, delicate and smooth skin texture (not overly realistic), " +
               "flowing fabric clothing, individual hair strands, neutral studio lighting, " +
               "clear focused gaze, natural demeanor.\n\n" +
               "PORTRAIT COMPOSITION: Extreme close-up, head and shoulders only, facial expressions focus.\n\n" +
               "Character facial expressions reference sheet, 3x3 grid layout showing 9 different facial expressions " +
               "(joy, anger, sorrow, surprise, fear, disgust, neutral, thinking, tired). " +
               "EXACTLY 3 rows by 3 columns grid layout, 9 cells total, each cell contains ONE distinct expression.\n\n" +
               "Character Face Description: " + appearance + "\n" +
               "Personality: " + personality + "\n\n" +
               "CRITICAL CONSTRAINTS:\n" +
               "- Close-up portrait shots ONLY (head and shoulders)\n" +
               "- NO full body, NO lower body, NO legs\n" +
               "- Focus on facial features, expressions, and head\n" +
               "- SOLID FLAT BACKGROUND - Plain solid color background ONLY (white, light gray, or black). " +
               "NO patterns, NO gradients, NO environmental elements\n" +
               "- Consistent character design across all 9 expressions\n" +
               "- EXACTLY 3x3 grid composition, 3 rows and 3 columns, 9 equal-sized cells\n\n" +
               "Negative Prompt: nsfw, text, watermark, label, signature, bad anatomy, deformed, low quality, " +
               "writing, letters, logo, interface, ui, username, website, chinese characters, chinese text, " +
               "english text, korean text, japanese text, any text, any characters, any letters, numbers, symbols, " +
               "subtitles, captions, title, full body, standing, legs, feet, full-length portrait, wide shot, " +
               "environmental background, patterned background, gradient background, 2D illustration, hand-drawn, " +
               "anime 2D, flat shading, cel shading, toon shading, cartoon 2D, paper cutout, translucent, ghostly, " +
               "ethereal, glowing aura, overly photorealistic, hyper-realistic skin, photorealistic rendering, " +
               "black and white, grayscale, monochrome, desaturated, sepia, muted colors, " +
               "2x3 grid, 2x4 grid, 3x2 grid, 6-grid, 4-grid, 2x2 grid, any grid layout other than 3x3";
    }

    /**
     * 3D风格三视图提示词
     */
    private String build3DThreeViewPrompt(String appearance, String personality) {
        return "full color illustration, NOT black and white, NOT grayscale, " +
               "Xianxia 3D animation character, semi-realistic style, Xianxia animation aesthetics, " +
               "high precision 3D modeling, PBR shading with soft translucency, subsurface scattering, " +
               "ambient occlusion, delicate and smooth skin texture (not overly realistic), " +
               "flowing fabric clothing, individual hair strands, neutral studio lighting, " +
               "clear focused gaze, natural demeanor.\n\n" +
               "CHARACTER THREE-VIEW GENERATION TASK:\n" +
               "Generate a character three-view reference sheet (front, side, back views).\n\n" +
               "Character Description: " + appearance + "\n" +
               "Attributes: " + personality + "\n\n" +
               "COMPOSITION:\n" +
               "- Create vertical layout with 3 views: Front View, Side View (profile), Back View\n" +
               "- Full body standing pose, neutral expression\n" +
               "- SOLID FLAT BACKGROUND - Plain solid color background ONLY (white, light gray, or black). " +
               "NO patterns, NO gradients, NO environmental elements\n" +
               "- Each view should clearly show the character from the specified angle\n\n" +
               "CRITICAL REQUIREMENTS:\n" +
               "1. CONSISTENT CHARACTER DESIGN - All three views must show the SAME character with consistent " +
               "facial features, hair style, body proportions, and clothing\n" +
               "2. NO TEXT, NO LABELS - Pure image only, no text labels of any kind\n" +
               "3. PROPER ANATOMY - Ensure correct body proportions and natural stance for each view angle\n" +
               "4. NEUTRAL EXPRESSION - Use a calm, neutral face expression across all views\n" +
               "5. CLEAR ALIGNMENT - Front, side, and back views should be vertically aligned and " +
               "proportionally consistent\n\n" +
               "Negative Prompt: nsfw, text, watermark, label, signature, bad anatomy, deformed, low quality, " +
               "writing, letters, logo, interface, ui, username, website, chinese characters, english text, " +
               "patterned background, gradient background, scenery, environmental background, shadows on background, " +
               "2D illustration, hand-drawn, anime 2D, flat shading, cel shading, toon shading, cartoon 2D, " +
               "paper cutout, translucent, ghostly, ethereal, glowing aura, overly photorealistic, " +
               "hyper-realistic skin, photorealistic rendering, " +
               "black and white, grayscale, monochrome, desaturated, sepia, muted colors";
    }

    // ================= REAL风格提示词 =================

    /**
     * REAL风格九宫格表情提示词
     */
    private String buildREALExpressionPrompt(String appearance, String personality) {
        return "full color photograph, NOT black and white, NOT grayscale, " +
               "Professional portrait photography, photorealistic human, cinematic photography, " +
               "professional headshot, DSLR quality, 85mm lens, sharp focus, realistic skin texture, " +
               "visible pores, natural skin imperfections, subsurface scattering.\n\n" +
               "PORTRAIT COMPOSITION: Professional headshot composition, head and shoulders only, " +
               "facial expressions focus.\n\n" +
               "Character facial expressions reference sheet, 3x3 grid layout showing 9 different facial expressions " +
               "(joy, anger, sadness, surprise, fear, disgust, neutral, thinking, tired). " +
               "EXACTLY 3 rows by 3 columns grid layout, 9 cells total, each cell contains ONE distinct expression.\n\n" +
               "Character Face Description: " + appearance + "\n" +
               "Personality: " + personality + "\n\n" +
               "CRITICAL CONSTRAINTS:\n" +
               "- Close-up portrait shots ONLY (head and shoulders)\n" +
               "- NO full body, NO lower body, NO legs\n" +
               "- Focus on facial features, expressions, and head\n" +
               "- SOLID FLAT BACKGROUND - Plain solid color background ONLY (white or black). " +
               "NO patterns, NO gradients, NO environmental elements\n" +
               "- Consistent character design across all 9 expressions\n" +
               "- EXACTLY 3x3 grid composition, 3 rows and 3 columns, 9 equal-sized cells\n\n" +
               "Negative Prompt: nsfw, text, watermark, label, signature, bad anatomy, deformed, low quality, " +
               "writing, letters, logo, interface, ui, username, website, chinese characters, chinese text, " +
               "english text, korean text, japanese text, any text, any characters, any letters, numbers, symbols, " +
               "subtitles, captions, title, full body, standing, legs, feet, full-length portrait, wide shot, " +
               "environmental background, patterned background, gradient background, anime, cartoon, illustration, " +
               "3d render, cgi, 3d animation, painting, drawing, " +
               "black and white, grayscale, monochrome, desaturated, sepia, muted colors, " +
               "2x3 grid, 2x4 grid, 3x2 grid, 6-grid, 4-grid, 2x2 grid, any grid layout other than 3x3";
    }

    /**
     * REAL风格三视图提示词
     */
    private String buildREALThreeViewPrompt(String appearance, String personality) {
        return "full color photograph, NOT black and white, NOT grayscale, " +
               "Professional portrait photography, photorealistic human, cinematic photography, " +
               "fashion photography style, studio lighting, realistic skin texture, " +
               "natural fabric folds, detailed clothing materials.\n\n" +
               "CHARACTER THREE-VIEW GENERATION TASK:\n" +
               "Generate a character three-view reference sheet (front, side, back views).\n\n" +
               "Character Description: " + appearance + "\n" +
               "Attributes: " + personality + "\n\n" +
               "COMPOSITION:\n" +
               "- Create vertical layout with 3 views: Front View, Side View (profile), Back View\n" +
               "- Full body standing pose, neutral expression\n" +
               "- SOLID FLAT BACKGROUND - Plain solid color background ONLY (white or black). " +
               "NO patterns, NO gradients, NO environmental elements\n" +
               "- Each view should clearly show the character from the specified angle\n\n" +
               "CRITICAL REQUIREMENTS:\n" +
               "1. CONSISTENT CHARACTER DESIGN - All three views must show the SAME character with consistent " +
               "facial features, hair style, body proportions, and clothing\n" +
               "2. NO TEXT, NO LABELS - Pure image only, no text labels of any kind\n" +
               "3. PROPER ANATOMY - Ensure correct body proportions and natural stance for each view angle\n" +
               "4. NEUTRAL EXPRESSION - Use a calm, neutral face expression across all views\n" +
               "5. CLEAR ALIGNMENT - Front, side, and back views should be vertically aligned and " +
               "proportionally consistent\n\n" +
               "Negative Prompt: nsfw, text, watermark, label, signature, bad anatomy, deformed, low quality, " +
               "writing, letters, logo, interface, ui, username, website, chinese characters, english text, " +
               "patterned background, gradient background, scenery, environmental background, shadows on background, " +
               "anime, cartoon, illustration, 3d render, cgi, 3d animation, painting, drawing, " +
               "black and white, grayscale, monochrome, desaturated, sepia, muted colors";
    }

    // ================= ANIME风格提示词 =================

    /**
     * ANIME风格九宫格表情提示词
     */
    private String buildANIMEExpressionPrompt(String appearance, String personality, VisualStyle subStyle) {
        String stylePrefix = "";
        switch (subStyle) {
            case MANGA:
                stylePrefix = "Japanese manga style, colorful manga illustration, ";
                break;
            case INK:
                stylePrefix = "Chinese ink wash painting style, sumi-e, ";
                break;
            case CYBERPUNK:
                stylePrefix = "Cyberpunk anime style, neon lights, futuristic, ";
                break;
            default:
                break;
        }

        String colorConstraint = subStyle.isFullColor()
                ? "full color illustration, NOT black and white, NOT grayscale, "
                : "";

        return colorConstraint + stylePrefix +
               "Anime character, anime style, 2D anime art, " +
               "vibrant colors, rich color palette, clean linework, crisp outlines, detailed illustration.\n\n" +
               "PORTRAIT COMPOSITION: Anime portrait composition, head and shoulders only, " +
               "facial expressions focus.\n\n" +
               "Character facial expressions reference sheet, 3x3 grid layout showing 9 different facial expressions " +
               "(joy, anger, sadness, surprise, fear, disgust, neutral, thinking, tired). " +
               "EXACTLY 3 rows by 3 columns grid layout, 9 cells total, each cell contains ONE distinct expression.\n\n" +
               "Character Face Description: " + appearance + "\n" +
               "Personality: " + personality + "\n\n" +
               "CRITICAL CONSTRAINTS:\n" +
               "- Close-up portrait shots ONLY (head and shoulders)\n" +
               "- NO full body, NO lower body, NO legs\n" +
               "- Focus on facial features, expressions, and head\n" +
               "- SOLID FLAT BACKGROUND - Plain solid color background ONLY (white, light gray, or black). " +
               "NO patterns, NO gradients, NO environmental elements\n" +
               "- Consistent character design across all 9 expressions\n" +
               "- EXACTLY 3x3 grid composition, 3 rows and 3 columns, 9 equal-sized cells\n\n" +
               "Negative Prompt: nsfw, text, watermark, label, signature, bad anatomy, deformed, low quality, " +
               "writing, letters, logo, interface, ui, username, website, chinese characters, chinese text, " +
               "english text, korean text, japanese text, any text, any characters, any letters, numbers, symbols, " +
               "subtitles, captions, title, full body, standing, legs, feet, full-length portrait, wide shot, " +
               "environmental background, patterned background, gradient background, photorealistic, realistic, " +
               "photo, 3d, cgi, live action, hyper-realistic, skin texture, pores, " +
               "black and white, grayscale, monochrome, desaturated, sepia, muted colors, " +
               "2x3 grid, 2x4 grid, 3x2 grid, 6-grid, 4-grid, 2x2 grid, any grid layout other than 3x3";
    }

    /**
     * ANIME风格三视图提示词
     */
    private String buildANIMEThreeViewPrompt(String appearance, String personality, VisualStyle subStyle) {
        String stylePrefix = "";
        switch (subStyle) {
            case MANGA:
                stylePrefix = "Japanese manga style, colorful manga illustration, ";
                break;
            case INK:
                stylePrefix = "Chinese ink wash painting style, sumi-e, ";
                break;
            case CYBERPUNK:
                stylePrefix = "Cyberpunk anime style, neon lights, futuristic, ";
                break;
            default:
                break;
        }

        String colorConstraint = subStyle.isFullColor()
                ? "full color illustration, NOT black and white, NOT grayscale, "
                : "";

        return colorConstraint + stylePrefix +
               "Anime character, 2D anime art, " +
               "vibrant colors, rich color palette, character reference sheet, " +
               "clean linework, crisp outlines, anime style.\n\n" +
               "CHARACTER THREE-VIEW GENERATION TASK:\n" +
               "Generate a character three-view reference sheet (front, side, back views).\n\n" +
               "Character Description: " + appearance + "\n" +
               "Attributes: " + personality + "\n\n" +
               "COMPOSITION:\n" +
               "- Create vertical layout with 3 views: Front View, Side View (profile), Back View\n" +
               "- Full body standing pose, neutral expression\n" +
               "- SOLID FLAT BACKGROUND - Plain solid color background ONLY (white, light gray, or black). " +
               "NO patterns, NO gradients, NO environmental elements\n" +
               "- Each view should clearly show the character from the specified angle\n\n" +
               "CRITICAL REQUIREMENTS:\n" +
               "1. CONSISTENT CHARACTER DESIGN - All three views must show the SAME character with consistent " +
               "facial features, hair style, body proportions, and clothing\n" +
               "2. NO TEXT, NO LABELS - Pure image only, no text labels of any kind\n" +
               "3. PROPER ANATOMY - Ensure correct body proportions and natural stance for each view angle\n" +
               "4. NEUTRAL EXPRESSION - Use a calm, neutral face expression across all views\n" +
               "5. CLEAR ALIGNMENT - Front, side, and back views should be vertically aligned and " +
               "proportionally consistent\n\n" +
               "Negative Prompt: nsfw, text, watermark, label, signature, bad anatomy, deformed, low quality, " +
               "writing, letters, logo, interface, ui, username, website, chinese characters, english text, " +
               "patterned background, gradient background, scenery, environmental background, shadows on background, " +
               "photorealistic, realistic, photo, 3d, cgi, live action, hyper-realistic, skin texture, pores, " +
               "black and white, grayscale, monochrome, desaturated, sepia, muted colors";
    }
}
