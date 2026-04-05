package com.comic.ai.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 集级别旁白稿生成 Prompt Builder
 * 生成一段完整的旁白口播稿，后续由 NarrationAllocator 切分到各 shot
 */
@Slf4j
@Component
public class NarrationPromptBuilder {

    /**
     * 构建旁白稿生成的系统 prompt
     *
     * @param narrationPerspective 第一人称或第三人称
     * @param estimatedWordCount   预计旁白总字数（由 totalDuration 和 dialogueCount 动态计算得出）
     * @return 系统 prompt 文本
     */
    public String buildNarrationSystemPrompt(String narrationPerspective, int estimatedWordCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专业的漫剧解说旁白编剧。\n");
        sb.append("你的任务是根据剧本内容，撰写一段完整的「旁白口播稿」。\n\n");
        sb.append("【口播稿规格】\n");
        sb.append("- 总字数：约 ").append(estimatedWordCount).append(" 字（允许 ±15% 偏差）\n");
        sb.append("- 风格：中文口语，自然流畅，适合朗读\n");
        sb.append("- 结构：开场引入 → 中间推进 → 高潮转折 → 结尾收束\n");
        sb.append("- 必须有清晰的起承转合，句子之间逻辑递进，禁止跳跃、重复或突兀换话题\n\n");

        if ("first_person".equals(narrationPerspective)) {
            sb.append("【人称要求 - 第一人称 · 最高优先级】\n");
            sb.append("- 必须以主角口吻叙述，使用「我」来讲述故事\n");
            sb.append("- 禁止使用第三人称（他/她/主角名字）\n\n");
        } else if ("third_person".equals(narrationPerspective)) {
            sb.append("【人称要求 - 第三人称 · 最高优先级】\n");
            sb.append("- 必须以旁观者/上帝视角叙述，用「他/她」或角色名指代\n");
            sb.append("- 禁止使用第一人称「我」\n\n");
        }

        sb.append("【输出格式】\n");
        sb.append("- 仅输出一段纯文本旁白稿，不要 JSON，不要 markdown 代码块\n");
        sb.append("- 段落之间用空行分隔，每段 30-80 字为宜\n");
        sb.append("- 旁白稿中可以有「……」省略号表示节奏停顿，但禁止使用括号、感叹号过多\n\n");

        sb.append("【断句要求 - 重要】\n");
        sb.append("- 旁白稿中的每一句话必须以完整标点（。！？，）结尾\n");
        sb.append("- 禁止出现无标点的长句（超过 30 字无标点视为不合格）\n");
        sb.append("- 每 2-4 句话形成一个自然的「叙事单元」，便于后续切分到各分镜\n\n");

        sb.append("【内容要求】\n");
        sb.append("- 完整讲述本集故事情节，覆盖故事的开端，发展、高潮、结尾\n");
        sb.append("- 旁白是画面之外的声音，描述画面中角色看不到的信息（内心活动、背景信息、情绪外化等）\n");
        sb.append("- 避免重复剧本原文，用自己的语言重新叙述\n");
        return sb.toString();
    }

    /**
     * 构建旁白稿生成的用户 prompt
     *
     * @param episodeContent 集剧本 content 字段
     * @param characters     角色描述
     * @param totalDuration  目标总时长（秒），用于估算旁白总字数
     * @param dialogueCount  本集预计对白 shot 数量（用于估算旁白 shot 数量）
     * @return 用户 prompt 文本
     */
    public String buildNarrationUserPrompt(String episodeContent, String characters,
                                           int totalDuration, int dialogueCount) {
        // 估算旁白 shot 数和总字数
        int totalShots = Math.round((float) totalDuration / 3.0f);
        int narrationShots = Math.max(0, totalShots - dialogueCount);
        int estimatedWordCount = (int) (narrationShots * 11 * 1.15);

        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下剧本内容，撰写本集的旁白口播稿。\n\n");
        sb.append("【剧本内容】\n").append(episodeContent).append("\n\n");
        if (characters != null && !characters.isEmpty()) {
            sb.append("【角色信息】\n").append(characters).append("\n\n");
        }
        sb.append("【约束条件】\n");
        sb.append("- 目标总时长：").append(totalDuration).append(" 秒\n");
        sb.append("- 本集预计对白分镜数：").append(dialogueCount).append(" 镜\n");
        sb.append("- 预计旁白分镜数：").append(narrationShots).append(" 镜\n");
        sb.append("- 建议旁白稿总字数：约 ").append(estimatedWordCount).append(" 字（允许 ±15% 偏差）\n\n");
        sb.append("请开始撰写旁白口播稿：");
        return sb.toString();
    }
}