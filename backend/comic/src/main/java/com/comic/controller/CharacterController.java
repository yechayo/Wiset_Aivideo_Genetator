package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.CharacterDraftDTO;
import com.comic.service.character.CharacterExtractService;
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
public class CharacterController {

    private final CharacterExtractService characterExtractService;

    /**
     * 从剧本中提取角色
     * POST /api/characters/extract
     */
    @PostMapping("/extract")
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
    public Result<List<CharacterDraftDTO>> getProjectCharacters(@RequestParam String projectId) {
        List<CharacterDraftDTO> characters = characterExtractService.getProjectCharacters(projectId);
        return Result.ok(characters);
    }

    /**
     * 编辑角色特征
     * PUT /api/characters/{charId}
     */
    @PutMapping("/{charId}")
    public Result<Void> updateCharacter(@PathVariable String charId,
                                        @RequestBody CharacterDraftDTO dto) {
        characterExtractService.updateCharacter(charId, dto);
        return Result.ok();
    }

    /**
     * 确认所有角色
     * POST /api/characters/confirm
     */
    @PostMapping("/confirm")
    public Result<Void> confirmCharacters(@RequestBody Map<String, String> body) {
        String projectId = body.get("projectId");
        characterExtractService.confirmCharacters(projectId);
        return Result.ok();
    }
}
