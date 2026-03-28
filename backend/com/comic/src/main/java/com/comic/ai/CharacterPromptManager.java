package com.comic.ai;

import com.comic.entity.Character;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 角色图片生成提示词管理器
 * 结构：风格前缀（纯抽象风格） + 角色特征（具体外貌） + 任务指令（表情/三视图）
 */
@Component
public class CharacterPromptManager {

    private String getCharInfoStr(Character character, String key) {
        Map<String, Object> info = character.getCharacterInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    private boolean isNonHuman(Character character) {
        String species = getCharInfoStr(character, "species");
        return "ANTHRO_ANIMAL".equals(species) || "CREATURE".equals(species) || "ANIMAL".equals(species);
    }

    private String getSpeciesAwareBodyDescription(Character character) {
        String species = getCharInfoStr(character, "species");
        if (species == null) species = "HUMAN";
        switch (species) {
            case "ANTHRO_ANIMAL": return "正确的拟人化身体结构，保留动物特征（耳朵、尾巴、爪等）";
            case "CREATURE": return "正确的种族身体结构，展现该种族特有的体型和特征";
            case "ANIMAL": return "自然的动物形态，四足或飞行姿态，无衣物配饰";
            default: return "正确的人体结构";
        }
    }

    private String getSpeciesAwareExpressionFraming(Character character) {
        String species = getCharInfoStr(character, "species");
        if (species == null) species = "HUMAN";
        switch (species) {
            case "ANTHRO_ANIMAL": return "特写肖像镜头（头部和肩部），表情通过面部、耳朵、尾巴动态体现";
            case "CREATURE": return "特写肖像镜头，表情通过该种族特征部位体现";
            case "ANIMAL": return "特写镜头（头部），表情通过耳朵、眼睛、尾巴姿态体现，不要肩部以下";
            default: return "仅限特写肖像镜头（头部和肩部）";
        }
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
            return fromFrontendValue(code);
        }

        public static VisualStyle fromFrontendValue(String value) {
            if (value == null || value.isEmpty()) return D_3D;
            for (VisualStyle s : values()) {
                if (s.frontendValue.equalsIgnoreCase(value)) return s;
            }
            return D_3D;
        }

        /** 只有水墨风允许黑白 */
        public boolean isFullColor() {
            return this != INK;
        }
    }

    // ================= 公开方法 =================

    /**
     * 构建九宫格表情大全图提示词
     */
    public String buildExpressionGridPrompt(Character character, VisualStyle style) {
        String appearance = getCharInfoStr(character, "appearance");
        String personality = getCharInfoStr(character, "personality");
        String stylePrefix = buildCharacterStylePrefix(style);
        String negativePrompt = buildExpressionNegativePrompt(style);
        String expressionFraming = getSpeciesAwareExpressionFraming(character);

        return stylePrefix +
                "角色面部表情参考图，3行3列网格布局，展示9种不同的面部表情" +
                "（喜悦、愤怒、悲伤、惊讶、恐惧、厌恶、平静、思考、疲惫）。" +
                "严格为3行3列网格布局，共9个格子，每个格子包含一种独立的表情。\n\n" +
                "肖像构图：极致特写，仅头部和肩部，聚焦面部表情。\n\n" +
                "角色面部描述：" + appearance + "\n" +
                "性格特征：" + personality + "\n\n" +
                "关键约束：\n" +
                "- **" + expressionFraming + "**\n" +
                "- 不要全身，不要下半身，不要腿部\n" +
                "- 聚焦面部特征、表情和头部\n" +
                "- 纯色平铺背景（白色、浅灰色或黑色），不要图案、渐变、环境元素\n" +
                "- 所有9个表情中保持角色设计一致\n" +
                "- 严格为3行3列网格构图\n\n" +
                "负面提示词：" + negativePrompt;
    }

    /**
     * 构建三视图大全图提示词
     */
    public String buildThreeViewGridPrompt(Character character, VisualStyle style) {
        String appearance = getCharInfoStr(character, "appearance");
        String personality = getCharInfoStr(character, "personality");
        String stylePrefix = buildCharacterStylePrefix(style);
        String negativePrompt = buildThreeViewNegativePrompt(style);
        String bodyDesc = getSpeciesAwareBodyDescription(character);

        String postureReq = "ANIMAL".equals(getCharInfoStr(character, "species"))
                ? "- 自然姿态（站立、四足或飞行），表情平静\n"
                : "- 全身站立姿势，表情平静\n";

        return stylePrefix +
                "角色三视图参考图，生成角色三视图（正面、侧面、背面视图）。\n\n" +
                "角色面部描述：" + appearance + "\n" +
                "属性特征：" + personality + "\n\n" +
                "构图要求：\n" +
                "- 纵向布局，包含3个视图：正面视图、侧面视图（侧面轮廓）、背面视图\n" +
                postureReq +
                "- 纯色平铺背景（白色、浅灰色或黑色），不要图案、渐变、环境元素\n" +
                "- 每个视图应清晰展示角色在指定角度下的外观\n\n" +
                "关键要求：\n" +
                "1. 角色设计一致——三个视图必须展示同一个角色\n" +
                "2. 不要文字、不要标签——纯图像\n" +
                "3. **" + bodyDesc + "**——身体比例正确、姿态自然\n" +
                "4. 平静表情——所有视图使用中性面部表情\n" +
                "5. 清晰对齐——正面、侧面和背面视图纵向对齐，比例一致\n\n" +
                "负面提示词：" + negativePrompt;
    }

    // ================= 风格前缀（纯抽象风格，不含具体人物特征） =================

    /**
     * 人物风格前缀
     * 只包含画风、渲染质量、人物绘制风格，不包含具体外貌/服装/姿态/表情
     */
    private String buildCharacterStylePrefix(VisualStyle style) {
        switch (style) {
            case REAL:
                return "写实风格，照片级真人肖像，电影级摄影质感，" +
                       "8K超高清分辨率，专业肖像摄影，单反相机画质，85毫米镜头，锐利对焦，" +
                       "自然光效，体积光，柔和阴影，写实皮肤纹理，次表面散射效果。\n\n";
            case D_3D:
                return "写实3D CG角色，半写实风格，" +
                       "高精度3D建模，高面数模型，8K超高清分辨率，干净精细的3D渲染，" +
                       "PBR材质着色，柔和半透明质感，次表面散射效果，" +
                       "环境光遮蔽，细腻光滑的皮肤纹理（非过度写实），" +
                       "飘逸的布料衣物，独立的发丝，中性影棚灯光，HDRI环境光，" +
                       "清晰专注的目光，自然的神态。\n\n";
            case ANIME:
                return "日系动漫角色，2D动漫艺术，动漫风格，" +
                       "杰作级别，最高品质，官方原画级别，" +
                       "鲜艳色彩，丰富的调色板，干净线条，清晰轮廓，精细插画。\n\n";
            case MANGA:
                return "日本漫画风格，彩色漫画插画，2D动漫艺术，" +
                       "杰作级别，最高品质，官方原画级别，" +
                       "鲜艳色彩，丰富的调色板，干净线条，清晰轮廓，精细插画。\n\n";
            case INK:
                return "中国水墨画风格，水墨写意，2D艺术，" +
                       "杰作级别，最高品质，" +
                       "意境深远，墨色浓淡有致，干净线条，清晰轮廓。\n\n";
            case CYBERPUNK:
                return "赛博朋克动漫角色，2D动漫艺术，霓虹灯光，未来感，" +
                       "杰作级别，最高品质，官方原画级别，" +
                       "暗色调对比，鲜艳色彩，干净线条，清晰轮廓，精细插画。\n\n";
            default:
                return "高质量，杰作级别，精细插画。\n\n";
        }
    }

    // ================= 负面提示词 =================

    /**
     * 表情网格负面提示词（按风格排除不该出现的风格元素）
     */
    private String buildExpressionNegativePrompt(VisualStyle style) {
        // 通用负面词（所有风格都排除）
        String common = "色情内容、文字、水印、标签、签名、人体结构错误、畸形、低质量、" +
                "全身、站立、腿部、脚部、全身肖像、广角镜头、" +
                "环境背景、图案背景、渐变背景、" +
                "2行3列网格、2行4列网格、3行2列网格、6格网格、4格网格、2行2列网格、任何非3行3列的网格布局";

        // 按风格排除
        switch (style) {
            case REAL:
                return common + "、" +
                        "动漫、卡通、插画、3D渲染、计算机图形、3D动画、绘画、手绘";
            case D_3D:
                return common + "、" +
                        "2D插画、手绘、2D动漫、平面着色、赛璐璐着色、卡通着色、2D卡通、剪纸风格、" +
                        "半透明、幽灵般、空灵、发光光环、过度照片写实、超写实皮肤、照片级渲染";
            case ANIME:
            case MANGA:
                return common + "、" +
                        "照片写实、写实、照片、3D、计算机图形、真人实拍、超写实、皮肤纹理、毛孔";
            case INK:
                return common + "、" +
                        "照片写实、写实、照片、3D、计算机图形、真人实拍、超写实";
            case CYBERPUNK:
                return common + "、" +
                        "照片写实、写实、照片、3D、计算机图形、真人实拍、超写实、皮肤纹理、毛孔";
            default:
                return common;
        }
    }

    /**
     * 三视图负面提示词
     */
    private String buildThreeViewNegativePrompt(VisualStyle style) {
        // 通用负面词
        String common = "色情内容、文字、水印、标签、签名、人体结构错误、畸形、低质量、" +
                "图案背景、渐变背景、风景、环境背景、背景上的阴影";

        switch (style) {
            case REAL:
                return common + "、" +
                        "动漫、卡通、插画、3D渲染、计算机图形、3D动画、绘画、手绘";
            case D_3D:
                return common + "、" +
                        "2D插画、手绘、2D动漫、平面着色、赛璐璐着色、卡通着色、2D卡通、" +
                        "半透明、幽灵般、空灵、发光光环、过度照片写实、超写实皮肤、照片级渲染";
            case ANIME:
            case MANGA:
                return common + "、" +
                        "照片写实、写实、照片、3D、计算机图形、真人实拍、超写实、皮肤纹理、毛孔";
            case INK:
                return common + "、" +
                        "照片写实、写实、照片、3D、计算机图形、真人实拍、超写实";
            case CYBERPUNK:
                return common + "、" +
                        "照片写实、写实、照片、3D、计算机图形、真人实拍、超写实、皮肤纹理、毛孔";
            default:
                return common;
        }
    }
}
