package com.comic;

import com.comic.ai.CharacterPromptManager;
import com.comic.ai.image.ImageGenerationService;
import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import com.comic.service.character.CharacterImageGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Seedream 图片生成综合测试
 * 测试角色图片生成的完整流程（grid 大全图模式）
 *
 * 运行方式：mvn test -Dtest=SeedreamImageGenerationTest
 */
@SpringBootTest
@AutoConfigureMockMvc
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedreamImageGenerationTest extends BaseTest {

    @Autowired
    private ImageGenerationService imageGenerationService;

    @Autowired
    private CharacterImageGenerationService characterImageGenerationService;

    @Autowired
    private CharacterRepository characterRepository;

    @Autowired
    private CharacterPromptManager characterPromptManager;

    // 测试数据
    static String testCharId;
    static String testProjectId = "TEST-PROJECT-001";

    // ──────────────────────────────────────────────────────────────
    //  准备工作
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @Transactional
    void testSetupTestData() throws Exception {
        log.info("=== 准备测试数据 ===");

        loginIfNeeded();

        // 创建测试角色
        Character character = new Character();
        character.setCharId("TEST-CHAR-" + System.currentTimeMillis());
        character.setName("测试角色");
        character.setRole("主角");
        character.setProjectId(testProjectId);
        character.setPersonality("勇敢、正直、富有正义感");
        character.setAppearance("年轻男子，黑色短发，眼神坚毅，身穿白色古装长袍，腰间佩剑");
        character.setBackground("来自神秘的山门，肩负着拯救世界的使命");
        character.setVisualStyle("D_3D");
        character.setConfirmed(true);
        character.setLocked(false);

        characterRepository.insert(character);
        testCharId = character.getCharId();

        log.info("创建测试角色: charId={}, name={}", testCharId, character.getName());
        assertNotNull(character.getCharId());
    }

    // ──────────────────────────────────────────────────────────────
    //  基础服务测试
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void testImageGenerationServiceBasic() {
        log.info("=== 测试基础图片生成服务 ===");

        assertEquals("Seedream-Image", imageGenerationService.getServiceName());
        assertTrue(imageGenerationService.getAvailableConcurrentSlots() >= 0);

        log.info("图片生成服务信息: serviceName={}, availableSlots={}",
                imageGenerationService.getServiceName(),
                imageGenerationService.getAvailableConcurrentSlots());
    }

    // ──────────────────────────────────────────────────────────────
    //  提示词管理测试
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void testCharacterPromptManager() {
        log.info("=== 测试提示词管理器 ===");

        Character character = characterRepository.findByCharId(testCharId);
        assertNotNull(character);

        // 测试 3D 风格九宫格提示词
        String prompt3D = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);
        assertNotNull(prompt3D);
        assertTrue(prompt3D.contains("3D动漫风格"));
        assertTrue(prompt3D.contains("3×3网格布局"));
        assertTrue(prompt3D.contains(character.getAppearance()));

        log.info("3D风格九宫格提示词长度: {} char", prompt3D.length());

        // 测试 REAL 风格九宫格提示词
        String promptREAL = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.REAL);
        assertNotNull(promptREAL);
        assertTrue(promptREAL.contains("真人写实风格"));

        // 测试 ANIME 风格九宫格提示词
        String promptANIME = characterPromptManager.buildExpressionGridPrompt(
                character, CharacterPromptManager.VisualStyle.ANIME);
        assertNotNull(promptANIME);
        assertTrue(promptANIME.contains("2D动漫风格"));

        // 测试三视图提示词
        String threeViewPrompt = characterPromptManager.buildThreeViewGridPrompt(
                character, CharacterPromptManager.VisualStyle.D_3D);
        assertNotNull(threeViewPrompt);
        assertTrue(threeViewPrompt.contains("三视图参考表"));
        assertTrue(threeViewPrompt.contains("正面、侧面、背面"));

        log.info("提示词管理器测试通过");
    }

    // ──────────────────────────────────────────────────────────────
    //  九宫格生成测试
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void testSetVisualStyle() throws Exception {
        log.info("=== 测试设置视觉风格 ===");

        mockMvc.perform(put("/api/characters/" + testCharId + "/visual-style")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visualStyle\":\"D_3D\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put("/api/characters/" + testCharId + "/visual-style")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visualStyle\":\"REAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put("/api/characters/" + testCharId + "/visual-style")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visualStyle\":\"ANIME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        log.info("视觉风格设置测试通过");
    }

    @Test
    @Order(11)
    void testGenerateExpressionGrid() throws Exception {
        log.info("=== 测试九宫格大全图生成 ===");

        characterImageGenerationService.setVisualStyle(testCharId, "D_3D");

        MvcResult result = mockMvc.perform(post("/api/characters/" + testCharId + "/generate-expression")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        log.info("九宫格大全图生成请求已提交");

        Thread.sleep(2000);

        mockMvc.perform(get("/api/characters/" + testCharId + "/status")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visualStyle").value("D_3D"));

        log.info("九宫格大全图生成测试通过");
    }

    @Test
    @Order(12)
    void testGenerateThreeViewGrid() throws Exception {
        log.info("=== 测试三视图大全图生成 ===");

        mockMvc.perform(post("/api/characters/" + testCharId + "/generate-three-view")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        log.info("三视图大全图生成请求已提交");

        Thread.sleep(2000);

        log.info("三视图大全图生成测试通过");
    }

    @Test
    @Order(13)
    void testGenerateAllGrid() throws Exception {
        log.info("=== 测试一键生成 ===");

        mockMvc.perform(post("/api/characters/" + testCharId + "/generate-all")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        log.info("一键生成请求已提交");

        Thread.sleep(3000);

        mockMvc.perform(get("/api/characters/" + testCharId + "/status")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.charId").value(testCharId));

        log.info("一键生成测试通过");
    }

    // ──────────────────────────────────────────────────────────────
    //  配角测试（应跳过表情生成）
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void testSupportingCharacterGeneration() throws Exception {
        log.info("=== 测试配角图片生成（应跳过表情） ===");

        Character supporting = new Character();
        supporting.setCharId("TEST-CHAR-SUPPORTING-" + System.currentTimeMillis());
        supporting.setName("配角测试");
        supporting.setRole("配角");
        supporting.setProjectId(testProjectId);
        supporting.setPersonality("忠诚、守信");
        supporting.setAppearance("中年男子，身材魁梧，身穿黑色盔甲");
        supporting.setVisualStyle("D_3D");
        supporting.setConfirmed(true);

        characterRepository.insert(supporting);
        String supportingCharId = supporting.getCharId();

        // 配角尝试生成表情应该失败
        mockMvc.perform(post("/api/characters/" + supportingCharId + "/generate-expression")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());

        log.info("配角表情生成正确跳过");

        // 但可以生成三视图
        mockMvc.perform(post("/api/characters/" + supportingCharId + "/generate-three-view")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        log.info("配角三视图生成正常");
        Thread.sleep(2000);

        log.info("配角图片生成测试通过");
    }

    // ──────────────────────────────────────────────────────────────
    //  错误处理测试
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    void testInvalidVisualStyle() throws Exception {
        log.info("=== 测试无效视觉风格 ===");

        mockMvc.perform(put("/api/characters/" + testCharId + "/visual-style")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visualStyle\":\"INVALID_STYLE\"}"))
                .andExpect(status().isBadRequest());

        log.info("无效视觉风格正确被拒绝");
    }

    @Test
    @Order(42)
    void testNonExistentCharacter() {
        log.info("=== 测试不存在的角色 ===");

        try {
            characterImageGenerationService.setVisualStyle("NON-EXISTENT-CHAR", "D_3D");
            fail("应该抛出异常");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("角色不存在"));
        }

        log.info("不存在的角色正确被拒绝");
    }

    // ──────────────────────────────────────────────────────────────
    //  状态查询测试
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    void testGetGenerationStatus() throws Exception {
        log.info("=== 测试状态查询 ===");

        MvcResult result = mockMvc.perform(get("/api/characters/" + testCharId + "/status")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.charId").value(testCharId))
                .andExpect(jsonPath("$.data.name").value("测试角色"))
                .andExpect(jsonPath("$.data.role").value("主角"))
                .andExpect(jsonPath("$.data.visualStyle").exists())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        log.info("状态查询响应: {}", response);

        log.info("状态查询测试通过");
    }

    @Test
    @Order(51)
    void testGetCharacterDetail() throws Exception {
        log.info("=== 测试角色详情查询 ===");

        mockMvc.perform(get("/api/characters/" + testCharId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.charId").value(testCharId));

        log.info("角色详情查询测试通过");
    }

    // ──────────────────────────────────────────────────────────────
    //  重试生成测试
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    void testRetryGeneration() throws Exception {
        log.info("=== 测试重试生成 ===");

        mockMvc.perform(post("/api/characters/" + testCharId + "/retry/expression")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Thread.sleep(2000);

        mockMvc.perform(post("/api/characters/" + testCharId + "/retry/threeView")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Thread.sleep(2000);

        log.info("重试生成测试通过");
    }

    // ──────────────────────────────────────────────────────────────
    //  并发槽位测试
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(70)
    void testConcurrentSlots() {
        log.info("=== 测试并发槽位 ===");

        int initialSlots = imageGenerationService.getAvailableConcurrentSlots();
        log.info("初始可用槽位: {}", initialSlots);

        assertTrue(initialSlots >= 0 && initialSlots <= 2,
                "并发槽位应在 0-2 范围内");

        log.info("并发槽位测试通过");
    }

    // ──────────────────────────────────────────────────────────────
    //  清理工作
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(100)
    @Transactional
    void testCleanup() {
        log.info("=== 清理测试数据 ===");

        if (testCharId != null) {
            Character character = characterRepository.findByCharId(testCharId);
            if (character != null) {
                characterRepository.deleteById(character.getId());
                log.info("清理测试角色: {}", testCharId);
            }
        }

        log.info("测试数据清理完成");
    }
}
