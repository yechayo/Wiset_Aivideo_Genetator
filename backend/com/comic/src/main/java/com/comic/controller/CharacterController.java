package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.model.CharacterDraftModel;
import com.comic.dto.response.CharacterStatusResponse;
import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 角色管理接口
 */
@RestController
@RequestMapping("/api/characters")
@RequiredArgsConstructor
@Tag(name = "角色管理")
@SecurityRequirement(name = "bearerAuth")
public class CharacterController {

    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;
    private final CharacterRepository characterRepository;

    /**
     * 从剧本中提取角色
     */
    @PostMapping("/extract")
    @Operation(summary = "提取角色", description = "从剧本中自动提取角色信息。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<List<CharacterDraftModel>> extractCharacters(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        List<CharacterDraftModel> characters = characterExtractService.extractCharacters(projectId);
        return Result.ok(characters);
    }

    /**
     * 获取项目角色列表
     */
    @GetMapping
    @Operation(summary = "获取项目角色列表", description = "获取指定项目的所有角色信息。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<List<CharacterDraftModel>> getProjectCharacters(
            @Parameter(description = "项目ID", required = true) @RequestParam String projectId) {
        List<CharacterDraftModel> characters = characterExtractService.getProjectCharacters(projectId);
        return Result.ok(characters);
    }

    /**
     * 编辑角色特征
     */
    @PutMapping("/{charId}")
    @Operation(summary = "编辑角色", description = "更新角色信息。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> updateCharacter(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId,
            @RequestBody CharacterDraftModel dto) {
        characterExtractService.updateCharacter(charId, dto);
        return Result.ok();
    }

    /**
     * 确认所有角色
     */
    @PostMapping("/confirm")
    @Operation(summary = "确认角色", description = "确认项目的所有角色，锁定角色数据。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> confirmCharacters(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        characterExtractService.confirmCharacters(projectId);
        return Result.ok();
    }

    // ================= 角色图片生成相关接口 =================

    /**
     * 生成角色九宫格表情大全图
     */
    @PostMapping("/{charId}/generate-expression")
    @Operation(summary = "生成九宫格表情", description = "为指定角色生成九宫格表情大全图。配角会自动跳过。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> generateExpression(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId) {
        characterImageGenerationService.generateExpressionSheet(charId);
        return Result.ok();
    }

    /**
     * 设置角色视觉风格
     */
    @PutMapping("/{charId}/visual-style")
    @Operation(summary = "设置视觉风格", description = "设置角色的视觉风格（3D/REAL/ANIME）。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> setVisualStyle(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId,
            @RequestBody Map<String, String> body) {
        String visualStyle = body.get("visualStyle");
        characterImageGenerationService.setVisualStyle(charId, visualStyle);
        return Result.ok();
    }

    /**
     * 生成角色三视图大全图
     */
    @PostMapping("/{charId}/generate-three-view")
    @Operation(summary = "生成三视图", description = "为指定角色生成三视图大全图（正面、侧面、背面）。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> generateThreeView(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId) {
        characterImageGenerationService.generateThreeViewSheet(charId);
        return Result.ok();
    }

    /**
     * 一键生成全部（表情+三视图）
     */
    @PostMapping("/{charId}/generate-all")
    @Operation(summary = "一键生成", description = "为指定角色生成全部图片（九宫格表情+三视图）。配角会跳过表情生成。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> generateAll(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId) {
        characterImageGenerationService.generateAll(charId);
        return Result.ok();
    }

    /**
     * 重试生成
     */
    @PostMapping("/{charId}/retry/{type}")
    @Operation(summary = "重试生成", description = "重新生成指定类型的图片。type: expression 或 threeView。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> retryGeneration(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId,
            @Parameter(description = "生成类型: expression 或 threeView", required = true) @PathVariable String type) {
        characterImageGenerationService.retryGeneration(charId, type);
        return Result.ok();
    }

    /**
     * 获取生成状态
     */
    @GetMapping("/{charId}/status")
    @Operation(summary = "获取生成状态", description = "获取角色的图片生成状态和已生成的图片URL。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<CharacterStatusResponse> getGenerationStatus(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId) {
        Character character = characterRepository.findByCharId(charId);
        if (character == null) {
            return Result.fail("角色不存在");
        }

        CharacterStatusResponse dto = new CharacterStatusResponse();
        dto.setCharId(character.getCharId());
        dto.setName(character.getName());
        dto.setRole(character.getRole());
        dto.setExpressionStatus(character.getExpressionStatus());
        dto.setThreeViewStatus(character.getThreeViewStatus());
        dto.setExpressionError(character.getExpressionError());
        dto.setThreeViewError(character.getThreeViewError());
        dto.setIsGeneratingExpression(character.getIsGeneratingExpression());
        dto.setIsGeneratingThreeView(character.getIsGeneratingThreeView());
        dto.setVisualStyle(character.getVisualStyle());
        dto.setExpressionGridUrl(character.getExpressionGridUrl());
        dto.setThreeViewGridUrl(character.getThreeViewGridUrl());

        return Result.ok(dto);
    }

    /**
     * 获取单个角色详情
     */
    @GetMapping("/{charId}")
    @Operation(summary = "获取角色详情", description = "获取指定角色的详细信息。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<CharacterStatusResponse> getCharacterDetail(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId) {
        return getGenerationStatus(charId);
    }
}
