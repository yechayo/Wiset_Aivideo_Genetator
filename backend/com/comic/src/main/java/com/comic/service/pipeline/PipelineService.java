package com.comic.service.pipeline;

import com.comic.common.BusinessException;
import com.comic.common.ProjectStatus;
import com.comic.dto.response.ProjectListItemResponse;
import com.comic.dto.response.ProjectStatusResponse;
import com.comic.entity.Episode;
import com.comic.entity.EpisodeProduction;
import com.comic.entity.Project;
import com.comic.repository.EpisodeProductionRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.ProjectRepository;
import com.comic.entity.Character;
import com.comic.repository.CharacterRepository;
import com.comic.service.character.CharacterExtractService;
import com.comic.service.character.CharacterImageGenerationService;
import com.comic.service.production.EpisodeProductionService;
import com.comic.service.script.ScriptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 流水线编排服务
 * 驱动整个创作流程的状态流转
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService {

    private final ProjectRepository projectRepository;
    private final EpisodeRepository episodeRepository;
    private final EpisodeProductionRepository episodeProductionRepository;
    private final CharacterRepository characterRepository;
    private final ScriptService scriptService;
    private final CharacterExtractService characterExtractService;
    private final CharacterImageGenerationService characterImageGenerationService;
    private final EpisodeProductionService episodeProductionService;

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

        // 触发下一阶段的任务（异步，不阻塞状态更新）
        triggerNextStageAsync(projectId, nextStatus);
    }

    /**
     * 异步触发下一阶段任务，避免任务触发失败回滚状态更新
     */
    private void triggerNextStageAsync(String projectId, String status) {
        try {
            triggerNextStage(projectId, status);
        } catch (Exception e) {
            log.error("触发下一阶段任务失败（状态已更新）: projectId={}, status={}, error={}",
                    projectId, status, e.getMessage(), e);
        }
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
     * PRODUCING 阶段会自动查询剧集子阶段进度
     */
    public ProjectStatusResponse getProjectStatusDetail(String projectId) {
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在");
        }

        ProjectStatus status = ProjectStatus.fromCode(project.getStatus());

        ProjectStatusResponse dto = new ProjectStatusResponse();
        dto.setProjectId(project.getProjectId());
        dto.setCurrentStep(status.getFrontendStep());
        dto.setFailed(status.isFailed());
        dto.setReview(status.isReview());
        dto.setCompletedSteps(status.getCompletedSteps());
        dto.setAvailableActions(status.getAvailableActions());

        // PRODUCING 阶段：查询剧集子阶段，提供细粒度状态
        if (status == ProjectStatus.PRODUCING) {
            enrichProducingStatus(dto, projectId);
        } else {
            dto.setStatusCode(status.getCode());
            dto.setStatusDescription(status.getDescription());
            dto.setGenerating(status.isGenerating());
        }

        return dto;
    }

    /**
     * 查询剧集生产子阶段，填充细粒度状态到项目级响应
     */
    private void enrichProducingStatus(ProjectStatusResponse dto, String projectId) {
        try {
            List<Episode> episodes = episodeRepository.findByProjectId(projectId);
            // 找到正在生产的剧集
            Episode producingEpisode = null;
            for (Episode ep : episodes) {
                String prodStatus = ep.getProductionStatus();
                if ("IN_PROGRESS".equals(prodStatus)) {
                    producingEpisode = ep;
                    break;
                }
            }

            if (producingEpisode == null) {
                // 没有正在生产的剧集，仅返回可生产提示（查询接口不触发副作用）
                boolean hasProducible = false;
                for (Episode ep : episodes) {
                    if ("DONE".equals(ep.getStatus())
                            && (ep.getProductionStatus() == null
                                || "NOT_STARTED".equals(ep.getProductionStatus())
                                || "FAILED".equals(ep.getProductionStatus()))) {
                        hasProducible = true;
                        break;
                    }
                    if ("DRAFT".equals(ep.getStatus()) || "FAILED".equals(ep.getStatus())) {
                        hasProducible = true;
                        break;
                    }
                }

                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription(hasProducible ? "待启动生产" : "生产中");
                dto.setGenerating(false);
                return;
            }

            EpisodeProduction production = episodeProductionRepository.findByEpisodeId(producingEpisode.getId());
            if (production == null) {
                dto.setStatusCode("PRODUCING");
                dto.setStatusDescription("生产中");
                dto.setGenerating(true);
                return;
            }

            // 用剧集子阶段作为项目状态描述
            String episodeStatus = production.getStatus();
            String progressMsg = production.getProgressMessage();
            int progress = production.getProgressPercent() != null ? production.getProgressPercent() : 0;

            // 映射剧集状态到项目级statusCode
            dto.setStatusCode(mapEpisodeToProjectStatus(episodeStatus));
            dto.setStatusDescription(progressMsg != null ? progressMsg : "生产中");
            dto.setGenerating(isEpisodeGenerating(episodeStatus));

            // 附加生产进度百分比和子阶段
            dto.setProductionProgress(progress);
            dto.setProductionSubStage(episodeStatus);

            // 如果完成，更新项目状态
            if ("COMPLETED".equals(episodeStatus)) {
                Project project = projectRepository.findByProjectId(projectId);
                project.setStatus(ProjectStatus.COMPLETED.getCode());
                projectRepository.updateById(project);
                dto.setStatusCode(ProjectStatus.COMPLETED.getCode());
                dto.setStatusDescription("已完成");
                dto.setGenerating(false);
            }

        } catch (Exception e) {
            log.warn("查询剧集生产子阶段失败: projectId={}, error={}", projectId, e.getMessage());
            dto.setStatusCode("PRODUCING");
            dto.setStatusDescription("生产中");
            dto.setGenerating(true);
        }
    }

    /**
     * 将剧集子阶段映射到项目级状态码
     */
    private String mapEpisodeToProjectStatus(String episodeStatus) {
        if (episodeStatus == null) return "PRODUCING";
        switch (episodeStatus) {
            case "ANALYZING": return "PRODUCING";
            case "GRID_GENERATING": return "PRODUCING";
            case "GRID_FUSION_PENDING": return "PRODUCING";
            case "BUILDING_PROMPTS": return "PRODUCING";
            case "GENERATING": return "PRODUCING";
            case "GENERATING_SUBS": return "PRODUCING";
            case "COMPOSING": return "PRODUCING";
            case "COMPLETED": return "COMPLETED";
            case "FAILED": return "PRODUCING";
            default: return "PRODUCING";
        }
    }

    /**
     * 判断剧集子阶段是否为生成中
     */
    private boolean isEpisodeGenerating(String episodeStatus) {
        if (episodeStatus == null) return false;
        switch (episodeStatus) {
            case "ANALYZING":
            case "GRID_GENERATING":
            case "BUILDING_PROMPTS":
            case "GENERATING":
            case "GENERATING_SUBS":
            case "COMPOSING":
                return true;
            default:
                return false;
        }
    }

    /**
     * 获取用户的所有项目列表（带状态映射）
     */
    public List<ProjectListItemResponse> getProjectsByUserId(String userId) {
        List<Project> projects = projectRepository.findAllByUserId(userId);
        List<ProjectListItemResponse> result = new ArrayList<>();
        for (Project project : projects) {
            result.add(toListItemDTO(project));
        }
        return result;
    }

    /**
     * Project → ProjectListItemResponse 转换
     */
    private ProjectListItemResponse toListItemDTO(Project project) {
        ProjectStatus status = ProjectStatus.fromCode(project.getStatus());

        ProjectListItemResponse dto = new ProjectListItemResponse();
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
                // 异步触发所有角色图片生成
                generateAllCharacterImagesAsync(projectId);
                break;

            case PRODUCING:
                // 异步触发视频生产，避免阻塞 HTTP 响应
                CompletableFuture.runAsync(() -> {
                    try {
                        episodeProductionService.startProductionForProject(projectId);
                    } catch (Exception e) {
                        log.error("视频生产异常: projectId={}, error={}", projectId, e.getMessage(), e);
                    }
                });
                break;

            default:
                // 其他状态不需要触发任务
                break;
        }
    }

    /**
     * 异步生成所有角色的图片，完成后更新项目状态到 IMAGE_REVIEW
     */
    private void generateAllCharacterImagesAsync(String projectId) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Character> characters = characterRepository.findByProjectId(projectId);
                log.info("开始批量生成角色图片: projectId={}, characterCount={}", projectId, characters.size());

                int successCount = 0;
                int failCount = 0;
                for (Character character : characters) {
                    try {
                        characterImageGenerationService.generateAll(character.getCharId());
                        successCount++;
                    } catch (Exception e) {
                        log.warn("角色图片生成失败（继续处理下一个）: charId={}, error={}", character.getCharId(), e.getMessage());
                        failCount++;
                    }
                }

                log.info("角色图片批量生成完成: projectId={}, success={}, fail={}", projectId, successCount, failCount);

                // 无论是否有部分失败，都进入 IMAGE_REVIEW 让用户在前端检查
                Project project = projectRepository.findByProjectId(projectId);
                if (project != null && ProjectStatus.IMAGE_GENERATING.getCode().equals(project.getStatus())) {
                    project.setStatus(ProjectStatus.IMAGE_REVIEW.getCode());
                    projectRepository.updateById(project);
                    log.info("项目状态更新为 IMAGE_REVIEW: projectId={}", projectId);
                }
            } catch (Exception e) {
                log.error("批量生成角色图片异常: projectId={}", projectId, e);
                // 更新为失败状态
                Project project = projectRepository.findByProjectId(projectId);
                if (project != null && ProjectStatus.IMAGE_GENERATING.getCode().equals(project.getStatus())) {
                    project.setStatus(ProjectStatus.IMAGE_GENERATING_FAILED.getCode());
                    projectRepository.updateById(project);
                    log.info("项目状态更新为 IMAGE_GENERATING_FAILED: projectId={}", projectId);
                }
            }
        });
    }

    private String generateProjectId() {
        return "PROJ-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
