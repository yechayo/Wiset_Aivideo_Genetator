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
        sb.append("- 结构：开场引入 → 中间推进 → 高潮转折 → 结尾收束\n");
        sb.append("- 必须有清晰的起承转合，句子之间逻辑递进，禁止跳跃、重复或突兀换话题\n");
        sb.append("- 必须有完整的情感弧线：开头铺垫 → 中段蓄势/制造张力 → 高潮情绪爆发（愤怒/震撼/悲伤） → 结尾升华或留悬念\n");
        sb.append("- 禁止从头到尾情绪平淡、语调单一——旁白必须有起伏，让观众感受到情绪的递进\n\n");

        sb.append("【写作风格 - 小说阅读感 · 最高优先级】\n");
        sb.append("你的旁白不是视频的说明文，而是一篇微小说。必须同时满足以下5个要素：\n\n");

        sb.append("1.【具象修饰语】禁止只用光杆名词，必须在名词前加有质感的修饰语。\n");
        sb.append("  ❌ 错误：「他走进房间，看到地上很乱。」\n");
        sb.append("  ✅ 正确：「他推门而入，屋内一片狼藉，散落的书籍如同枯叶般铺满地板。」\n\n");

        sb.append("2.【心理侧写】角色有复杂表情时，不描述表情本身（「他很痛苦」），而是外化心理活动。\n");
        sb.append("  ❌ 错误：「男主看着女主，没有说话，转身走了。」\n");
        sb.append("  ✅ 正确：「他深深地看了她一眼，千言万语涌到嘴边，却又生生咽了回去。最终，他选择了沉默，决绝地转身离去。」\n\n");

        sb.append("3.【五感体验】大场景或战斗场景中至少调动两种感官（视觉+听觉/触觉/嗅觉）。\n");
        sb.append("  ❌ 错误：「这是一个可怕的森林，他在发抖。」\n");
        sb.append("  ✅ 正确：「四周死一般的寂静，只有脚踩枯枝的脆响刺耳回荡。空气中弥漫着腐烂的腥气，刺骨的寒意钻进衣领，让他不由自主地打了个寒颤。」\n\n");

        sb.append("4.【宿命感金句】转折处和结尾处少用「然后、接着」，多用宿命论连接词（殊不知、命运的齿轮、然而、这一刻……），把具体事件升华为命运探讨。\n");
        sb.append("  ❌ 错误：「男主打赢了BOSS，但自己也受了重伤。」\n");
        sb.append("  ✅ 正确：「这场战斗终于落幕。胜利的代价是惨痛的，鲜血染红了战甲。命运的齿轮在这一刻疯狂转动，没人知道，这位疲惫的胜利者，是否还能看到明天的太阳。」\n\n");

        sb.append("5.【长短句韵律】铺垫用长句（描写环境、心理），爆发用短句（动作、打击、惊吓），制造起伏节奏。\n");
        sb.append("  ❌ 错误（全碎片化）：「他冲上去了。速度快极了。一拳打在敌人脸上。敌人飞了出去。」\n");
        sb.append("  ✅ 正确（长短结合）：「电光火石之间，他动了。身影快得如同一道黑色闪电，瞬间撕裂了空气。砰！沉闷的撞击声响起，敌人像断了线的风筝一样倒飞而出。」\n\n");

        sb.append("【绝对禁止清单】\n");
        sb.append("- 禁止「然后他……」「接着……」「只见……」「话说……」等流水账连接词\n");
        sb.append("- 禁止「他很痛苦」「非常害怕」「十分愤怒」等抽象情绪标签\n");
        sb.append("- 禁止连续3句以上使用相同句式或相同主语开头\n");
        sb.append("- 禁止旁白中直接引用对白原文（如：他说：「……」）\n\n");

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
        sb.append("- 禁止「看图说话」：旁白不是画面的说明文字，不要逐镜描述画面里有什么。旁白是连续的叙事流，每一句都要承接上一句、推进下一句\n");
        sb.append("- 段落之间必须有语义承接：用主题承接、对比承接、因果承接、情绪承接等方式确保连贯性\n");
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
        int estimatedWordCount = (int) (narrationShots * 17 * 1.15);

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