package com.comic.service.production;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryboardTemplateParserTest {

    @Test
    void extractVisualLabels_shouldKeepOnlyVisualLines() {
        String template = String.join("\n",
                "（动作）冲刺穿过街道",
                "（环境）雨夜霓虹",
                "（状态）紧张",
                "（表情）皱眉",
                "（走位）林晓星｜位置锁=前景中｜姿态锁=奔跑｜朝向锁=右前｜道具锁=手电",
                "内声：「林晓星：快跑」",
                "（音效）轰隆"
        );

        String visual = StoryboardTemplateParser.extractVisualLabels(template);

        assertEquals(String.join("\n",
                "（动作）冲刺穿过街道",
                "（环境）雨夜霓虹",
                "（状态）紧张",
                "（表情）皱眉"
        ), visual);
    }

    @Test
    void extractVisualLabels_shouldReturnEmptyForNullOrEmpty() {
        assertEquals("", StoryboardTemplateParser.extractVisualLabels(null));
        assertEquals("", StoryboardTemplateParser.extractVisualLabels(""));
    }

    @Test
    void extractDialogue_shouldParseCharacterDialogue() {
        String template = "内声：「林晓星：我们到了」";

        List<StoryboardTemplateParser.DialogueLine> lines = StoryboardTemplateParser.extractDialogue(template);

        assertEquals(1, lines.size());
        assertEquals("林晓星", lines.get(0).getSpeaker());
        assertEquals("我们到了", lines.get(0).getContent());
        assertEquals("", lines.get(0).getTone());
    }

    @Test
    void extractDialogue_shouldParseNarrationFormat() {
        String template = "内声：（旁白）「夜色压城」";

        List<StoryboardTemplateParser.DialogueLine> lines = StoryboardTemplateParser.extractDialogue(template);

        assertEquals(1, lines.size());
        assertEquals("旁白", lines.get(0).getSpeaker());
        assertEquals("夜色压城", lines.get(0).getContent());
    }

    @Test
    void extractDialogue_shouldReturnEmptyWhenNoInnerVoice() {
        String template = String.join("\n",
                "（动作）抬头",
                "（音效）风声"
        );

        List<StoryboardTemplateParser.DialogueLine> lines = StoryboardTemplateParser.extractDialogue(template);

        assertTrue(lines.isEmpty());
    }

    @Test
    void extractDialogue_shouldParseCharacterDialogueWithChineseQuotes() {
        String template = "\u5185\u58f0\uff1a\u201c\u6797\u6653\u661f\uff1a\u51fa\u53d1\u201d";

        List<StoryboardTemplateParser.DialogueLine> lines = StoryboardTemplateParser.extractDialogue(template);

        assertEquals(1, lines.size());
        assertEquals("\u6797\u6653\u661f", lines.get(0).getSpeaker());
        assertEquals("\u51fa\u53d1", lines.get(0).getContent());
    }

    @Test
    void innerVoiceKeywordInPlainText_shouldNotBeTreatedAsInnerVoiceLabel() {
        String plainText = "\u5185\u58f0\u98ce\u8d77\u4e86";

        List<StoryboardTemplateParser.DialogueLine> lines = StoryboardTemplateParser.extractDialogue(plainText);

        assertTrue(lines.isEmpty());
        assertFalse(StoryboardTemplateParser.isTemplateText(
                String.join("\n",
                        "(\u52a8\u4f5c)\u62ac\u5934",
                        "\u5185\u58f0\u98ce\u8d77\u4e86"
                )));
    }

    @Test
    void extractAudioEffects_shouldExtractAllAudioLines() {
        String template = String.join("\n",
                "（音效）雷声",
                "（动作）后退",
                "(音效)脚步回响"
        );

        List<String> effects = StoryboardTemplateParser.extractAudioEffects(template);

        assertEquals(Arrays.asList("雷声", "脚步回响"), effects);
    }

    @Test
    void extractBlocking_shouldParseFullWidthAndHalfWidthFormats() {
        String template = String.join("\n",
                "（走位）林晓星｜位置锁=前景中｜姿态锁=奔跑｜朝向锁=右前｜道具锁=手电",
                "(走位)陈墨|位置锁=后景左|姿态锁=站立|朝向锁=左前|道具锁=雨伞"
        );

        List<StoryboardTemplateParser.BlockingInfo> infos = StoryboardTemplateParser.extractBlocking(template);

        assertEquals(2, infos.size());

        StoryboardTemplateParser.BlockingInfo first = infos.get(0);
        assertEquals("林晓星", first.getCharacter());
        assertEquals("前景中", first.getPositionLock());
        assertEquals("奔跑", first.getPoseLock());
        assertEquals("右前", first.getFacingLock());
        assertEquals("手电", first.getPropLock());

        StoryboardTemplateParser.BlockingInfo second = infos.get(1);
        assertEquals("陈墨", second.getCharacter());
        assertEquals("后景左", second.getPositionLock());
        assertEquals("站立", second.getPoseLock());
        assertEquals("左前", second.getFacingLock());
        assertEquals("雨伞", second.getPropLock());
    }

    @Test
    void isTemplateText_shouldRecognizeFullWidthLabels() {
        String text = String.join("\n",
                "（动作）抬头",
                "（环境）雨夜"
        );

        assertTrue(StoryboardTemplateParser.isTemplateText(text));
    }

    @Test
    void isTemplateText_shouldRecognizeHalfWidthLabels() {
        String text = String.join("\n",
                "(状态)紧张",
                "(音效)轰隆"
        );

        assertTrue(StoryboardTemplateParser.isTemplateText(text));
    }

    @Test
    void isTemplateText_shouldRejectFreeText() {
        String text = "他在雨夜里奔跑，心里很紧张。";

        assertFalse(StoryboardTemplateParser.isTemplateText(text));
    }
}
