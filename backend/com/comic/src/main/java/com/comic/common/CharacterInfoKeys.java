package com.comic.common;

public final class CharacterInfoKeys {
    // 基础信息
    public static final String CHAR_ID = "charId";
    public static final String NAME = "name";
    public static final String ROLE = "role";             // 主角/反派/配角
    public static final String ALIAS = "alias";            // 称谓
    public static final String PERSONALITY = "personality";
    public static final String VOICE = "voice";
    public static final String APPEARANCE = "appearance";          // 中文外貌描述
    public static final String APPEARANCE_PROMPT = "appearancePrompt"; // 英文生图提示词（关键）
    public static final String PROFESSION = "profession";  // 职业（含隐藏身份）
    public static final String BACKGROUND = "background";

    // 主角扩展字段
    public static final String MOTIVATION = "motivation";      // 核心动机
    public static final String VALUES = "values";              // 价值观
    public static final String WEAKNESS = "weakness";          // 恐惧与弱点
    public static final String RELATIONSHIPS = "relationships";// 核心关系
    public static final String HABITS = "habits";              // 语言风格/行为习惯

    // 状态与确认
    public static final String CONFIRMED = "confirmed";
    public static final String VISUAL_STYLE = "visualStyle";

    // 图片生成
    public static final String THREE_VIEWS_URL = "threeViewsUrl";
    public static final String EXPRESSION_IMAGE_URL = "expressionImageUrl";
    public static final String EXPRESSION_STATUS = "expressionStatus";
    public static final String THREE_VIEW_STATUS = "threeViewStatus";
    public static final String EXPRESSION_ERROR = "expressionError";
    public static final String THREE_VIEW_ERROR = "threeViewError";
    public static final String IS_GENERATING_EXPRESSION = "isGeneratingExpression";
    public static final String IS_GENERATING_THREE_VIEW = "isGeneratingThreeView";
    public static final String EXPRESSION_GRID_URL = "expressionGridUrl";
    public static final String THREE_VIEW_GRID_URL = "threeViewGridUrl";
    public static final String EXPRESSION_GRID_PROMPT = "expressionGridPrompt";
    public static final String THREE_VIEW_GRID_PROMPT = "threeViewGridPrompt";

    private CharacterInfoKeys() {}
}