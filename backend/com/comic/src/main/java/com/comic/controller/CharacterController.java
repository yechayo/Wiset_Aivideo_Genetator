package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.model.CharacterDraftModel;
import com.comic.dto.request.CharacterUpdateRequest;
import com.comic.dto.response.CharacterListItemResponse;
import com.comic.dto.response.CharacterStatusResponse;
import com.comic.dto.response.PaginatedResponse;
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

@RestController
@RequestMapping("/api/projects/{projectId}/characters")
@RequiredArgsConstructor
@Tag(name = "角色管理")
@SecurityRequirement(name = "bearerAuth")
public class CharacterController {

    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;

    // ================= CRUD 接口 =================

    @GetMapping
    @Operation(summary = "获取项目角色列表（分页）")
    public Result<PaginatedResponse<CharacterListItemResponse>> getProjectCharacters(
            @PathVariable String projectId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "角色类型筛选") @RequestParam(required = false) String role,
            @Parameter(description = "角色名称模糊搜索") @RequestParam(required = false) String name) {
        return Result.ok(characterExtractService.getProjectCharactersPage(projectId, role, name, page, size));
    }

    @GetMapping("/{charId}")
    @Operation(summary = "获取角色详情")
    public Result<CharacterStatusResponse> getCharacterDetail(
            @PathVariable String projectId,
            @PathVariable String charId) {
        return Result.ok(characterImageGenerationService.getGenerationStatus(charId));
    }

    @PostMapping("/extract")
    @Operation(summary = "提取角色", description = "从剧本中自动提取角色信息")
    public Result<List<CharacterDraftModel>> extractCharacters(@PathVariable String projectId) {
        List<CharacterDraftModel> characters = characterExtractService.extractCharacters(projectId);
        return Result.ok(characters);
    }

    @PutMapping("/{charId}")
    @Operation(summary = "更新角色信息")
    public Result<Void> updateCharacter(
            @PathVariable String projectId,
            @PathVariable String charId,
            @RequestBody CharacterUpdateRequest dto) {
        characterExtractService.updateCharacter(charId, dto);
        return Result.ok();
    }

    @DeleteMapping("/{charId}")
    @Operation(summary = "删除角色（逻辑删除）")
    public Result<Void> deleteCharacter(
            @PathVariable String projectId,
            @PathVariable String charId) {
        characterExtractService.deleteCharacter(charId);
        return Result.ok();
    }

    // ================= 确认接口 =================

    @PostMapping("/confirm")
    @Operation(summary = "确认角色", description = "确认项目的所有角色，锁定角色数据")
    public Result<Void> confirmCharacters(@PathVariable String projectId) {
        characterExtractService.confirmCharacters(projectId);
        return Result.ok();
    }

    @PostMapping("/images/confirm")
    @Operation(summary = "确认图片", description = "确认项目的所有角色图片，锁定素材并进入分镜生成")
    public Result<Void> confirmImages(@PathVariable String projectId) {
        characterImageGenerationService.confirmImages(projectId);
        return Result.ok();
    }

    // ================= 图片生成接口 =================

    @PostMapping("/{charId}/generate/expression")
    @Operation(summary = "生成九宫格表情")
    public Result<Void> generateExpression(
            @PathVariable String projectId,
            @PathVariable String charId) {
        characterImageGenerationService.generateExpressionSheet(charId);
        return Result.ok();
    }

    @PostMapping("/{charId}/generate/three-view")
    @Operation(summary = "生成三视图")
    public Result<Void> generateThreeView(
            @PathVariable String projectId,
            @PathVariable String charId) {
        characterImageGenerationService.generateThreeViewSheet(charId);
        return Result.ok();
    }

    @PostMapping("/{charId}/generate/all")
    @Operation(summary = "一键生成（表情+三视图）")
    public Result<Void> generateAll(
            @PathVariable String projectId,
            @PathVariable String charId) {
        characterImageGenerationService.generateAll(charId);
        return Result.ok();
    }

    @PutMapping("/{charId}/visual-style")
    @Operation(summary = "设置视觉风格")
    public Result<Void> setVisualStyle(
            @PathVariable String projectId,
            @PathVariable String charId,
            @RequestBody Map<String, String> body) {
        String visualStyle = body.get("visualStyle");
        characterImageGenerationService.setVisualStyle(charId, visualStyle);
        return Result.ok();
    }

    @PostMapping("/{charId}/retry/{type}")
    @Operation(summary = "重试生成")
    public Result<Void> retryGeneration(
            @PathVariable String projectId,
            @PathVariable String charId,
            @PathVariable String type) {
        characterImageGenerationService.retryGeneration(charId, type);
        return Result.ok();
    }

    @GetMapping("/{charId}/status")
    @Operation(summary = "获取生成状态")
    public Result<CharacterStatusResponse> getGenerationStatus(
            @PathVariable String projectId,
            @PathVariable String charId) {
        return Result.ok(characterImageGenerationService.getGenerationStatus(charId));
    }
}