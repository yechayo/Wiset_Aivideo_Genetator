package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.CharacterDraftDTO;
import com.comic.service.character.CharacterExtractService;
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

    /**
     * 从剧本中提取角色
     * POST /api/characters/extract
     */
    @PostMapping("/extract")
    @Operation(summary = "提取角色", description = "从剧本中自动提取角色信息。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<List<CharacterDraftDTO>> extractCharacters(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        List<CharacterDraftDTO> characters = characterExtractService.extractCharacters(projectId);
        return Result.ok(characters);
    }

    /**
     * 获取项目角色列表
     * GET /api/characters?projectId={projectId}
     */
    @GetMapping
    @Operation(summary = "获取项目角色列表", description = "获取指定项目的所有角色信息。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<List<CharacterDraftDTO>> getProjectCharacters(
            @Parameter(description = "项目ID", required = true) @RequestParam String projectId) {
        List<CharacterDraftDTO> characters = characterExtractService.getProjectCharacters(projectId);
        return Result.ok(characters);
    }

    /**
     * 编辑角色特征
     * PUT /api/characters/{charId}
     */
    @PutMapping("/{charId}")
    @Operation(summary = "编辑角色", description = "更新角色信息。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> updateCharacter(
            @Parameter(description = "角色ID", required = true) @PathVariable String charId,
            @RequestBody CharacterDraftDTO dto) {
        characterExtractService.updateCharacter(charId, dto);
        return Result.ok();
    }

    /**
     * 确认所有角色
     * POST /api/characters/confirm
     */
    @PostMapping("/confirm")
    @Operation(summary = "确认角色", description = "确认项目的所有角色，锁定角色数据。需要在 Header 中传递 JWT Token：Authorization: Bearer {token}")
    public Result<Void> confirmCharacters(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        characterExtractService.confirmCharacters(projectId);
        return Result.ok();
    }
}
