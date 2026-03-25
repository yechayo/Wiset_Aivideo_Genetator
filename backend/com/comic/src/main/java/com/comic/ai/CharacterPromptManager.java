package com.comic.ai;

import com.comic.entity.Character;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 角色图片生成提示词管理器
 * 从 promptManager.md 迁移的专业提示词模板
 */
@Component
public class CharacterPromptManager {

    private String getCharInfoStr(Character character, String key) {
        Map<String, Object> info = character.getCharacterInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

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
        String appearance = getCharInfoStr(character, "appearance");
        String personality = getCharInfoStr(character, "personality");

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
        String appearance = getCharInfoStr(character, "appearance");
        String personality = getCharInfoStr(character, "personality");

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
        return "全彩插画，非黑白，非灰度，" +
               "仙侠3D动画角色，半写实风格，仙侠动画美学，" +
               "高精度3D建模，PBR材质着色配合柔和半透明质感，次表面散射效果，" +
               "环境光遮蔽，细腻光滑的皮肤纹理（非过度写实），" +
               "飘逸的布料衣物，独立的发丝，中性影棚灯光，" +
               "清晰专注的目光，自然的神态。\n\n" +
               "肖像构图：极致特写，仅头部和肩部，聚焦面部表情。\n\n" +
               "角色面部表情参考图，3行3列网格布局，展示9种不同的面部表情" +
               "（喜悦、愤怒、悲伤、惊讶、恐惧、厌恶、平静、思考、疲惫）。" +
               "严格为3行3列网格布局，共9个格子，每个格子包含一种独立的表情。\n\n" +
               "角色面部描述：" + appearance + "\n" +
               "性格特征：" + personality + "\n\n" +
               "关键约束：\n" +
               "- 仅限特写肖像镜头（头部和肩部）\n" +
               "- 不要全身，不要下半身，不要腿部\n" +
               "- 聚焦面部特征、表情和头部\n" +
               "- 纯色平铺背景——仅使用纯色背景（白色、浅灰色或黑色）。" +
               "不要图案，不要渐变，不要环境元素\n" +
               "- 所有9个表情中保持角色设计一致\n" +
               "- 严格为3行3列网格构图，3行3列，9个等大的格子\n\n" +
               "负面提示词：色情内容、文字、水印、标签、签名、人体结构错误、畸形、低质量、" +
               "书写文字、字母、标志、界面、用户界面、用户名、网址、中文字符、中文文字、" +
               "英文文字、韩文文字、日文文字、任何文字、任何字符、任何字母、数字、符号、" +
               "字幕、标题文字、标题、全身、站立、腿部、脚部、全身肖像、广角镜头、" +
               "环境背景、图案背景、渐变背景、2D插画、手绘、" +
               "2D动漫、平面着色、赛璐璐着色、卡通着色、2D卡通、剪纸风格、半透明、幽灵般、" +
               "空灵、发光光环、过度照片写实、超写实皮肤、照片级渲染、" +
               "黑白、灰度、单色、去饱和、棕褐色、色彩暗淡、" +
               "2行3列网格、2行4列网格、3行2列网格、6格网格、4格网格、2行2列网格、任何非3行3列的网格布局";
    }

    /**
     * 3D风格三视图提示词
     */
    private String build3DThreeViewPrompt(String appearance, String personality) {
        return "全彩插画，非黑白，非灰度，" +
               "仙侠3D动画角色，半写实风格，仙侠动画美学，" +
               "高精度3D建模，PBR材质着色配合柔和半透明质感，次表面散射效果，" +
               "环境光遮蔽，细腻光滑的皮肤纹理（非过度写实），" +
               "飘逸的布料衣物，独立的发丝，中性影棚灯光，" +
               "清晰专注的目光，自然的神态。\n\n" +
               "角色三视图生成任务：\n" +
               "生成角色三视图参考图（正面、侧面、背面视图）。\n\n" +
               "角色描述：" + appearance + "\n" +
               "属性特征：" + personality + "\n\n" +
               "构图要求：\n" +
               "- 创建纵向布局，包含3个视图：正面视图、侧面视图（侧面轮廓）、背面视图\n" +
               "- 全身站立姿势，表情平静\n" +
               "- 纯色平铺背景——仅使用纯色背景（白色、浅灰色或黑色）。" +
               "不要图案，不要渐变，不要环境元素\n" +
               "- 每个视图应清晰展示角色在指定角度下的外观\n\n" +
               "关键要求：\n" +
               "1. 角色设计一致——三个视图必须展示同一个角色，面部特征、发型、身体比例和服装保持一致\n" +
               "2. 不要文字、不要标签——纯图像，不要任何形式的文字标签\n" +
               "3. 正确的人体结构——确保每个视图角度下身体比例正确、姿态自然\n" +
               "4. 平静表情——所有视图中使用平静、中性的面部表情\n" +
               "5. 清晰对齐——正面、侧面和背面视图应纵向对齐，比例一致\n\n" +
               "负面提示词：色情内容、文字、水印、标签、签名、人体结构错误、畸形、低质量、" +
               "书写文字、字母、标志、界面、用户界面、用户名、网址、中文字符、英文文字、" +
               "图案背景、渐变背景、风景、环境背景、背景上的阴影、" +
               "2D插画、手绘、2D动漫、平面着色、赛璐璐着色、卡通着色、2D卡通、" +
               "剪纸风格、半透明、幽灵般、空灵、发光光环、过度照片写实、" +
               "超写实皮肤、照片级渲染、" +
               "黑白、灰度、单色、去饱和、棕褐色、色彩暗淡";
    }

    // ================= REAL风格提示词 =================

    /**
     * REAL风格九宫格表情提示词
     */
    private String buildREALExpressionPrompt(String appearance, String personality) {
        return "全彩照片，非黑白，非灰度，" +
               "专业肖像摄影，照片级真人，电影级摄影，" +
               "专业证件照，单反相机画质，85毫米镜头，锐利对焦，写实皮肤纹理，" +
               "可见毛孔，自然皮肤瑕疵，次表面散射效果。\n\n" +
               "肖像构图：专业证件照构图，仅头部和肩部，" +
               "聚焦面部表情。\n\n" +
               "角色面部表情参考图，3行3列网格布局，展示9种不同的面部表情" +
               "（喜悦、愤怒、悲伤、惊讶、恐惧、厌恶、平静、思考、疲惫）。" +
               "严格为3行3列网格布局，共9个格子，每个格子包含一种独立的表情。\n\n" +
               "角色面部描述：" + appearance + "\n" +
               "性格特征：" + personality + "\n\n" +
               "关键约束：\n" +
               "- 仅限特写肖像镜头（头部和肩部）\n" +
               "- 不要全身，不要下半身，不要腿部\n" +
               "- 聚焦面部特征、表情和头部\n" +
               "- 纯色平铺背景——仅使用纯色背景（白色或黑色）。" +
               "不要图案，不要渐变，不要环境元素\n" +
               "- 所有9个表情中保持角色设计一致\n" +
               "- 严格为3行3列网格构图，3行3列，9个等大的格子\n\n" +
               "负面提示词：色情内容、文字、水印、标签、签名、人体结构错误、畸形、低质量、" +
               "书写文字、字母、标志、界面、用户界面、用户名、网址、中文字符、中文文字、" +
               "英文文字、韩文文字、日文文字、任何文字、任何字符、任何字母、数字、符号、" +
               "字幕、标题文字、标题、全身、站立、腿部、脚部、全身肖像、广角镜头、" +
               "环境背景、图案背景、渐变背景、动漫、卡通、插画、" +
               "3D渲染、计算机图形、3D动画、绘画、手绘、" +
               "黑白、灰度、单色、去饱和、棕褐色、色彩暗淡、" +
               "2行3列网格、2行4列网格、3行2列网格、6格网格、4格网格、2行2列网格、任何非3行3列的网格布局";
    }

    /**
     * REAL风格三视图提示词
     */
    private String buildREALThreeViewPrompt(String appearance, String personality) {
        return "全彩照片，非黑白，非灰度，" +
               "专业肖像摄影，照片级真人，电影级摄影，" +
               "时尚摄影风格，影棚灯光，写实皮肤纹理，" +
               "自然布料褶皱，精细的服装材质。\n\n" +
               "角色三视图生成任务：\n" +
               "生成角色三视图参考图（正面、侧面、背面视图）。\n\n" +
               "角色描述：" + appearance + "\n" +
               "属性特征：" + personality + "\n\n" +
               "构图要求：\n" +
               "- 创建纵向布局，包含3个视图：正面视图、侧面视图（侧面轮廓）、背面视图\n" +
               "- 全身站立姿势，表情平静\n" +
               "- 纯色平铺背景——仅使用纯色背景（白色或黑色）。" +
               "不要图案，不要渐变，不要环境元素\n" +
               "- 每个视图应清晰展示角色在指定角度下的外观\n\n" +
               "关键要求：\n" +
               "1. 角色设计一致——三个视图必须展示同一个角色，面部特征、发型、身体比例和服装保持一致\n" +
               "2. 不要文字、不要标签——纯图像，不要任何形式的文字标签\n" +
               "3. 正确的人体结构——确保每个视图角度下身体比例正确、姿态自然\n" +
               "4. 平静表情——所有视图中使用平静、中性的面部表情\n" +
               "5. 清晰对齐——正面、侧面和背面视图应纵向对齐，比例一致\n\n" +
               "负面提示词：色情内容、文字、水印、标签、签名、人体结构错误、畸形、低质量、" +
               "书写文字、字母、标志、界面、用户界面、用户名、网址、中文字符、英文文字、" +
               "图案背景、渐变背景、风景、环境背景、背景上的阴影、" +
               "动漫、卡通、插画、3D渲染、计算机图形、3D动画、绘画、手绘、" +
               "黑白、灰度、单色、去饱和、棕褐色、色彩暗淡";
    }

    // ================= ANIME风格提示词 =================

    /**
     * ANIME风格九宫格表情提示词
     */
    private String buildANIMEExpressionPrompt(String appearance, String personality, VisualStyle subStyle) {
        String stylePrefix = "";
        switch (subStyle) {
            case MANGA:
                stylePrefix = "日本漫画风格，彩色漫画插画，";
                break;
            case INK:
                stylePrefix = "中国水墨画风格，水墨写意，";
                break;
            case CYBERPUNK:
                stylePrefix = "赛博朋克动漫风格，霓虹灯光，未来感，";
                break;
            default:
                break;
        }

        String colorConstraint = subStyle.isFullColor()
                ? "全彩插画，非黑白，非灰度，"
                : "";

        return colorConstraint + stylePrefix +
               "动漫角色，动漫风格，2D动漫艺术，" +
               "鲜艳色彩，丰富的调色板，干净线条，清晰轮廓，精细插画。\n\n" +
               "肖像构图：动漫肖像构图，仅头部和肩部，" +
               "聚焦面部表情。\n\n" +
               "角色面部表情参考图，3行3列网格布局，展示9种不同的面部表情" +
               "（喜悦、愤怒、悲伤、惊讶、恐惧、厌恶、平静、思考、疲惫）。" +
               "严格为3行3列网格布局，共9个格子，每个格子包含一种独立的表情。\n\n" +
               "角色面部描述：" + appearance + "\n" +
               "性格特征：" + personality + "\n\n" +
               "关键约束：\n" +
               "- 仅限特写肖像镜头（头部和肩部）\n" +
               "- 不要全身，不要下半身，不要腿部\n" +
               "- 聚焦面部特征、表情和头部\n" +
               "- 纯色平铺背景——仅使用纯色背景（白色、浅灰色或黑色）。" +
               "不要图案，不要渐变，不要环境元素\n" +
               "- 所有9个表情中保持角色设计一致\n" +
               "- 严格为3行3列网格构图，3行3列，9个等大的格子\n\n" +
               "负面提示词：色情内容、文字、水印、标签、签名、人体结构错误、畸形、低质量、" +
               "书写文字、字母、标志、界面、用户界面、用户名、网址、中文字符、中文文字、" +
               "英文文字、韩文文字、日文文字、任何文字、任何字符、任何字母、数字、符号、" +
               "字幕、标题文字、标题、全身、站立、腿部、脚部、全身肖像、广角镜头、" +
               "环境背景、图案背景、渐变背景、照片写实、写实、" +
               "照片、3D、计算机图形、真人实拍、超写实、皮肤纹理、毛孔、" +
               "黑白、灰度、单色、去饱和、棕褐色、色彩暗淡、" +
               "2行3列网格、2行4列网格、3行2列网格、6格网格、4格网格、2行2列网格、任何非3行3列的网格布局";
    }

    /**
     * ANIME风格三视图提示词
     */
    private String buildANIMEThreeViewPrompt(String appearance, String personality, VisualStyle subStyle) {
        String stylePrefix = "";
        switch (subStyle) {
            case MANGA:
                stylePrefix = "日本漫画风格，彩色漫画插画，";
                break;
            case INK:
                stylePrefix = "中国水墨画风格，水墨写意，";
                break;
            case CYBERPUNK:
                stylePrefix = "赛博朋克动漫风格，霓虹灯光，未来感，";
                break;
            default:
                break;
        }

        String colorConstraint = subStyle.isFullColor()
                ? "全彩插画，非黑白，非灰度，"
                : "";

        return colorConstraint + stylePrefix +
               "动漫角色，2D动漫艺术，" +
               "鲜艳色彩，丰富的调色板，角色参考图，" +
               "干净线条，清晰轮廓，动漫风格。\n\n" +
               "角色三视图生成任务：\n" +
               "生成角色三视图参考图（正面、侧面、背面视图）。\n\n" +
               "角色描述：" + appearance + "\n" +
               "属性特征：" + personality + "\n\n" +
               "构图要求：\n" +
               "- 创建纵向布局，包含3个视图：正面视图、侧面视图（侧面轮廓）、背面视图\n" +
               "- 全身站立姿势，表情平静\n" +
               "- 纯色平铺背景——仅使用纯色背景（白色、浅灰色或黑色）。" +
               "不要图案，不要渐变，不要环境元素\n" +
               "- 每个视图应清晰展示角色在指定角度下的外观\n\n" +
               "关键要求：\n" +
               "1. 角色设计一致——三个视图必须展示同一个角色，面部特征、发型、身体比例和服装保持一致\n" +
               "2. 不要文字、不要标签——纯图像，不要任何形式的文字标签\n" +
               "3. 正确的人体结构——确保每个视图角度下身体比例正确、姿态自然\n" +
               "4. 平静表情——所有视图中使用平静、中性的面部表情\n" +
               "5. 清晰对齐——正面、侧面和背面视图应纵向对齐，比例一致\n\n" +
               "负面提示词：色情内容、文字、水印、标签、签名、人体结构错误、畸形、低质量、" +
               "书写文字、字母、标志、界面、用户界面、用户名、网址、中文字符、英文文字、" +
               "图案背景、渐变背景、风景、环境背景、背景上的阴影、" +
               "照片写实、写实、照片、3D、计算机图形、真人实拍、超写实、皮肤纹理、毛孔、" +
               "黑白、灰度、单色、去饱和、棕褐色、色彩暗淡";
    }
}