package com.comic.controller;

import com.comic.common.Result;
import com.comic.dto.model.CharacterDraftModel;
import com.comic.dto.request.CharacterUpdateRequest;
import com.comic.dto.response.CharacterListItemResponse;
import com.comic.dto.response.CharacterStatusResponse;
import com.comic.dto.response.PaginatedResponse;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import com.comic.statemachine.enums.ProjectEventType;
import com.comic.statemachine.service.ProjectStateMachineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
    private final ProjectStateMachineService stateMachineService;

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
        // 通过状态机触发角色提取
        boolean accepted = stateMachineService.sendEvent(projectId, ProjectEventType.EXTRACT_CHARACTERS);
        if (!accepted) {
            return Result.fail("当前状态不允许提取角色");
        }
        // 注意：角色提取是异步的，这里只表示事件已接受
        // 实际角色列表需要通过 GET /characters 接口获取
        return Result.ok(new ArrayList<>());
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
        // 通过状态机触发角色确认
        stateMachineService.sendEvent(projectId, ProjectEventType.CONFIRM_CHARACTERS);
        return Result.ok();
    }

    @PostMapping("/images/confirm")
    @Operation(summary = "确认图片", description = "确认项目的所有角色图片，锁定素材并进入分镜生成")
    public Result<Void> confirmImages(@PathVariable String projectId) {
        // 通过状态机触发图片确认
        stateMachineService.sendEvent(projectId, ProjectEventType.CONFIRM_IMAGES);
        return Result.ok();
    }

    @PostMapping("/images/generate-all")
    @Operation(summary = "生成所有角色图片", description = "批量生成项目所有角色的图片（表情+三视图），走状态机流程")
    public Result<Void> generateAllImages(@PathVariable String projectId) {
        // 通过状态机触发图片生成
        boolean accepted = stateMachineService.sendEvent(projectId, ProjectEventType.GENERATE_IMAGES);
        if (!accepted) {
            return Result.fail("当前状态不允许生成图片");
        }
        return Result.ok();
    }

    // ================= 图片生成接口 =================

    @GetMapping("/{charId}/status")
    @Operation(summary = "获取生成状态")
    public Result<CharacterStatusResponse> getGenerationStatus(
            @PathVariable String projectId,
            @PathVariable String charId) {
        return Result.ok(characterImageGenerationService.getGenerationStatus(charId));
    }
}