package com.comic;

import com.comic.ai.CharacterPromptManager;
import com.comic.entity.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Seedream 图片生成服务单元测试
 * 测试核心功能而不依赖实际的 API 调用
 *
 * 运行方式：mvn test -Dtest=SeedreamImageServiceUnitTest
 */
@SpringBootTest
class SeedreamImageServiceUnitTest {

    @Autowired
    private CharacterPromptManager characterPromptManager;

    @Test
    @DisplayName("测试视觉风格枚举")
    void testVisualStyleEnum() {
        CharacterPromptManager.VisualStyle style3D = CharacterPromptManager.VisualStyle.D_3D;
        assertEquals("3D", style3D.getCode());

        CharacterPromptManager.VisualStyle styleREAL = CharacterPromptManager.VisualStyle.REAL;
        assertEquals("REAL", styleREAL.getCode());

        CharacterPromptManager.VisualStyle styleANIME = CharacterPromptManager.VisualStyle.ANIME;
        assertEquals("ANIME", styleANIME.getCode());
    }

    @Test
    @DisplayName("测试提示词管理器 - 3D风格九宫格")
    void test3DExpressionPrompt() {
        Character character = new Character();
        character.setName("测试角色");
        character.setAppearance("年轻男子，黑色短发，眼神坚毅");
        character.setPersonality("勇敢、正直");

        String prompt = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);

        assertNotNull(prompt);
        assertTrue(prompt.contains("3D动漫风格"));
        assertTrue(prompt.contains("3×3网格布局"));
        assertTrue(prompt.contains("年轻男子，黑色短发，眼神坚毅"));
        assertTrue(prompt.contains("勇敢、正直"));
        assertTrue(prompt.contains("负面提示词"));

        System.out.println("3D风格九宫格提示词:");
        System.out.println(prompt);
    }

    @Test
    @DisplayName("测试提示词管理器 - REAL风格九宫格")
    void testREALExpressionPrompt() {
        Character character = new Character();
        character.setName("测试角色");
        character.setAppearance("年轻女子，长发飘飘");
        character.setPersonality("温柔、善良");

        String prompt = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.REAL);

        assertNotNull(prompt);
        assertTrue(prompt.contains("真人写实风格"));
        assertTrue(prompt.contains("3×3网格布局"));
        assertTrue(prompt.contains("年轻女子，长发飘飘"));

        System.out.println("REAL风格九宫格提示词:");
        System.out.println(prompt);
    }

    @Test
    @DisplayName("测试提示词管理器 - ANIME风格九宫格")
    void testANIMEExpressionPrompt() {
        Character character = new Character();
        character.setName("动漫角色");
        character.setAppearance("可爱的少女，粉色双马尾");
        character.setPersonality("活泼、开朗");

        String prompt = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.ANIME);

        assertNotNull(prompt);
        assertTrue(prompt.contains("2D动漫风格"));
        assertTrue(prompt.contains("3×3网格布局"));
        assertTrue(prompt.contains("可爱的少女，粉色双马尾"));

        System.out.println("ANIME风格九宫格提示词:");
        System.out.println(prompt);
    }

    @Test
    @DisplayName("测试提示词管理器 - 3D风格三视图")
    void test3DThreeViewPrompt() {
        Character character = new Character();
        character.setName("测试角色");
        character.setAppearance("身穿白色古装长袍，腰间佩剑");
        character.setPersonality("英勇无畏");

        String prompt = characterPromptManager.buildThreeViewGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);

        assertNotNull(prompt);
        assertTrue(prompt.contains("3D动漫风格"));
        assertTrue(prompt.contains("三视图参考表"));
        assertTrue(prompt.contains("正面、侧面、背面"));
        assertTrue(prompt.contains("身穿白色古装长袍，腰间佩剑"));

        System.out.println("3D风格三视图提示词:");
        System.out.println(prompt);
    }

    @Test
    @DisplayName("测试提示词管理器 - REAL风格三视图")
    void testREALThreeViewPrompt() {
        Character character = new Character();
        character.setName("测试角色");
        character.setAppearance("现代都市女性，职业装");
        character.setPersonality("专业、干练");

        String prompt = characterPromptManager.buildThreeViewGridPrompt(
                character, CharacterPromptManager.VisualStyle.REAL);

        assertNotNull(prompt);
        assertTrue(prompt.contains("真人写实风格"));
        assertTrue(prompt.contains("三视图参考表"));
        assertTrue(prompt.contains("正面、侧面、背面"));

        System.out.println("REAL风格三视图提示词:");
        System.out.println(prompt);
    }

    @Test
    @DisplayName("测试提示词管理器 - ANIME风格三视图")
    void testANIMEThreeViewPrompt() {
        Character character = new Character();
        character.setName("动漫角色");
        character.setAppearance("魔法少女，魔法棒和翅膀");
        character.setPersonality("充满好奇心");

        String prompt = characterPromptManager.buildThreeViewGridPrompt(
                character, CharacterPromptManager.VisualStyle.ANIME);

        assertNotNull(prompt);
        assertTrue(prompt.contains("2D动漫风格"));
        assertTrue(prompt.contains("三视图参考表"));

        System.out.println("ANIME风格三视图提示词:");
        System.out.println(prompt);
    }

    @Test
    @DisplayName("测试空值处理")
    void testNullHandling() {
        Character character = new Character();
        character.setName("测试角色");
        character.setAppearance(null);
        character.setPersonality(null);

        // 应该不会抛出异常
        String prompt = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);

        assertNotNull(prompt);
        assertTrue(prompt.contains("外貌："));
        assertTrue(prompt.contains("性格："));

        System.out.println("空值处理测试通过");
    }

    @Test
    @DisplayName("测试负面提示词包含")
    void testNegativePrompts() {
        Character character = new Character();
        character.setName("测试角色");
        character.setAppearance("测试外貌");
        character.setPersonality("测试性格");

        String prompt = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);

        // 检查负面提示词是否包含
        assertTrue(prompt.contains("nsfw"));
        assertTrue(prompt.contains("text"));
        assertTrue(prompt.contains("watermark"));
        assertTrue(prompt.contains("label"));
        assertTrue(prompt.contains("signature"));

        System.out.println("负面提示词测试通过");
    }

    @Test
    @DisplayName("测试提示词长度")
    void testPromptLength() {
        Character character = new Character();
        character.setName("测试角色");
        character.setAppearance("中等长度的外貌描述");
        character.setPersonality("中等长度的性格描述");

        String expressionPrompt = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);

        String threeViewPrompt = characterPromptManager.buildThreeViewGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);

        // 提示词应该有合理的长度（包含详细的指令）
        assertTrue(expressionPrompt.length() > 200, "表情提示词应该足够详细");
        assertTrue(threeViewPrompt.length() > 200, "三视图提示词应该足够详细");

        System.out.println("表情提示词长度: " + expressionPrompt.length());
        System.out.println("三视图提示词长度: " + threeViewPrompt.length());
    }

    @Test
    @DisplayName("测试不同风格的关键词差异")
    void testStyleSpecificKeywords() {
        Character character = new Character();
        character.setName("测试角色");
        character.setAppearance("测试外貌");
        character.setPersonality("测试性格");

        String prompt3D = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);
        String promptREAL = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.REAL);
        String promptANIME = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.ANIME);

        // 每种风格应该有其特定的关键词
        assertTrue(prompt3D.contains("3D动漫风格") || prompt3D.contains("PBR材质渲染"));
        assertTrue(promptREAL.contains("真人写实风格") || promptREAL.contains("摄影"));
        assertTrue(promptANIME.contains("2D动漫风格") || promptANIME.contains("漫画"));

        System.out.println("风格特定关键词测试通过");
    }
}