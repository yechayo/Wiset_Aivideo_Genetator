package com.comic.service.pipeline;

import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.dto.ProjectListItemDTO;
import com.comic.dto.ProjectStatusDTO;
import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.script.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 流水线编排服务
 * 驱动整个创作流程的状态流转
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService {

    private final ProjectRepository projectRepository;
    private final ScriptService scriptService;
    private final CharacterExtractService characterExtractService;

    /**
     * 创建新项目
     */
    @Transactional
    public String createProject(String userId, String storyPrompt, String genre,
                               String targetAudience, Integer totalEpisodes,
                               Integer episodeDuration, String visualStyle) {
        Project project = new Project();
        project.setProjectId(generateProjectId());
        project.setUserId(userId);
        project.setStoryPrompt(storyPrompt);
        project.setGenre(genre);
        project.setTargetAudience(targetAudience);
        project.setTotalEpisodes(totalEpisodes);
        project.setEpisodeDuration(episodeDuration);
        project.setVisualStyle(visualStyle);
        project.setStatus(ProjectStatus.DRAFT.getCode());

        projectRepository.insert(project);

        log.info("项目已创建: projectId={}, userId={}", project.getProjectId(), userId);
        return project.getProjectId();
    }

    /**
     * 推进流水线
     * 根据当前状态和事件，自动流转到下一状态并触发相应任务
     */
    @Transactional
    public void advancePipeline(String projectId, String event) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        String currentStatus = project.getStatus();
        String nextStatus = calculateNextStatus(currentStatus, event);

        if (nextStatus == null) {
            throw new BusinessException("无法从状态 " + currentStatus + " 通过事件 " + event + " 转换");
        }

        // 更新状态
        project.setStatus(nextStatus);
        projectRepository.updateById(project);

        log.info("流水线状态流转: projectId={}, {} -> {}", projectId, currentStatus, nextStatus);

        // 触发下一阶段的任务
        triggerNextStage(projectId, nextStatus);
    }

    /**
     * 获取项目状态
     */
    public Project getProjectStatus(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    /**
     * 获取项目状态详情（包含前端步骤映射和可用操作）
     */
    public ProjectStatusDTO getProjectStatusDetail(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        ProjectStatus status = ProjectStatus.fromCode(project.getStatus());

        ProjectStatusDTO dto = new ProjectStatusDTO();
        dto.setProjectId(project.getProjectId());
        dto.setStatusCode(status.getCode());
        dto.setStatusDescription(status.getDescription());
        dto.setCurrentStep(status.getFrontendStep());
        dto.setGenerating(status.isGenerating());
        dto.setFailed(status.isFailed());
        dto.setReview(status.isReview());
        dto.setCompletedSteps(status.getCompletedSteps());
        dto.setAvailableActions(status.getAvailableActions());

        return dto;
    }

    /**
     * 获取用户的所有项目列表（带状态映射）
     */
    public List<ProjectListItemDTO> getProjectsByUserId(String userId) {
        List<Project> projects = projectRepository.findAllByUserId(userId);
        List<ProjectListItemDTO> result = new ArrayList<>();
        for (Project project : projects) {
            result.add(toListItemDTO(project));
        }
        return result;
    }

    /**
     * Project → ProjectListItemDTO 转换
     */
    private ProjectListItemDTO toListItemDTO(Project project) {
        ProjectStatus status = ProjectStatus.fromCode(project.getStatus());

        ProjectListItemDTO dto = new ProjectListItemDTO();
        dto.setProjectId(project.getProjectId());
        dto.setStoryPrompt(project.getStoryPrompt());
        dto.setGenre(project.getGenre());
        dto.setTargetAudience(project.getTargetAudience());
        dto.setTotalEpisodes(project.getTotalEpisodes());
        dto.setEpisodeDuration(project.getEpisodeDuration());
        dto.setVisualStyle(project.getVisualStyle());
        dto.setStatusCode(status.getCode());
        dto.setStatusDescription(status.getDescription());
        dto.setCurrentStep(status.getFrontendStep());
        dto.setGenerating(status.isGenerating());
        dto.setFailed(status.isFailed());
        dto.setReview(status.isReview());
        dto.setCompletedSteps(status.getCompletedSteps());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setUpdatedAt(project.getUpdatedAt());

        return dto;
    }

    // ================= 私有方法 =================

    private String calculateNextStatus(String currentStatus, String event) {
        switch (event) {
            case "start_script_generation":
                return ProjectStatus.OUTLINE_GENERATING.getCode();
            case "script_generated":
                return ProjectStatus.SCRIPT_REVIEW.getCode();
            case "script_confirmed":
                return ProjectStatus.SCRIPT_CONFIRMED.getCode();
            case "script_revision_requested":
                return ProjectStatus.OUTLINE_REVIEW.getCode();
            case "start_character_extraction":
                return ProjectStatus.CHARACTER_EXTRACTING.getCode();
            case "characters_extracted":
                return ProjectStatus.CHARACTER_REVIEW.getCode();
            case "characters_confirmed":
                return ProjectStatus.CHARACTER_CONFIRMED.getCode();
            case "start_image_generation":
                return ProjectStatus.IMAGE_GENERATING.getCode();
            case "images_generated":
                return ProjectStatus.IMAGE_REVIEW.getCode();
            case "image_confirmed":
                return ProjectStatus.ASSET_LOCKED.getCode();
            case "start_production":
                return ProjectStatus.PRODUCING.getCode();
            case "production_completed":
                return ProjectStatus.COMPLETED.getCode();
            default:
                return null;
        }
    }

    private void triggerNextStage(String projectId, String status) {
        ProjectStatus projectStatus = ProjectStatus.fromCode(status);
        switch (projectStatus) {
            case OUTLINE_GENERATING:
                // 触发剧本大纲生成（两级生成：第一步）
                scriptService.generateScriptOutline(projectId);
                break;

            case CHARACTER_EXTRACTING:
                // 触发角色提取
                characterExtractService.extractCharacters(projectId);
                break;

            case IMAGE_GENERATING:
                // TODO: 触发图像生成
                log.info("图像生成功能待实现: projectId={}", projectId);
                break;

            case PRODUCING:
                // TODO: 触发视频生产
                log.info("视频生产功能待实现: projectId={}", projectId);
                break;

            default:
                // 其他状态不需要触发任务
                break;
        }
    }

    private String generateProjectId() {
        return "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
