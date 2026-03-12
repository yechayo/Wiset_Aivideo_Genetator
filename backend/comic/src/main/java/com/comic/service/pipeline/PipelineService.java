package com.comic.service.pipeline;

import com.comic.common.BusinessException;
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

    // 状态流转图
    private static final Map<String, Set<String>> STATE_TRANSITIONS = new HashMap<>();

    static {
        // DRAFT 状态可以转换到
        STATE_TRANSITIONS.put("DRAFT", new HashSet<>(Arrays.asList("SCRIPT_GENERATING")));

        // SCRIPT_GENERATING 状态可以转换到
        STATE_TRANSITIONS.put("SCRIPT_GENERATING", new HashSet<>(Arrays.asList(
            "SCRIPT_REVIEW", "SCRIPT_GENERATING_FAILED"
        )));

        // SCRIPT_REVIEW 状态可以转换到
        STATE_TRANSITIONS.put("SCRIPT_REVIEW", new HashSet<>(Arrays.asList(
            "SCRIPT_CONFIRMED", "SCRIPT_REVISION_REQUESTED"
        )));

        // SCRIPT_REVISION_REQUESTED 状态可以转换到
        STATE_TRANSITIONS.put("SCRIPT_REVISION_REQUESTED", new HashSet<>(Arrays.asList(
            "SCRIPT_GENERATING"
        )));

        // SCRIPT_CONFIRMED 状态可以转换到
        STATE_TRANSITIONS.put("SCRIPT_CONFIRMED", new HashSet<>(Arrays.asList(
            "CHARACTER_EXTRACTING"
        )));

        // CHARACTER_EXTRACTING 状态可以转换到
        STATE_TRANSITIONS.put("CHARACTER_EXTRACTING", new HashSet<>(Arrays.asList(
            "CHARACTER_REVIEW", "CHARACTER_EXTRACTING_FAILED"
        )));

        // CHARACTER_REVIEW 状态可以转换到
        STATE_TRANSITIONS.put("CHARACTER_REVIEW", new HashSet<>(Arrays.asList(
            "CHARACTER_CONFIRMED"
        )));

        // CHARACTER_CONFIRMED 状态可以转换到
        STATE_TRANSITIONS.put("CHARACTER_CONFIRMED", new HashSet<>(Arrays.asList(
            "IMAGE_GENERATING"
        )));

        // IMAGE_GENERATING 状态可以转换到
        STATE_TRANSITIONS.put("IMAGE_GENERATING", new HashSet<>(Arrays.asList(
            "IMAGE_REVIEW", "IMAGE_GENERATING_FAILED"
        )));

        // IMAGE_REVIEW 状态可以转换到
        STATE_TRANSITIONS.put("IMAGE_REVIEW", new HashSet<>(Arrays.asList(
            "ASSET_LOCKED"
        )));

        // ASSET_LOCKED 状态可以转换到
        STATE_TRANSITIONS.put("ASSET_LOCKED", new HashSet<>(Arrays.asList(
            "PRODUCING"
        )));

        // PRODUCING 状态可以转换到
        STATE_TRANSITIONS.put("PRODUCING", new HashSet<>(Arrays.asList(
            "COMPLETED"
        )));
    }

    /**
     * 创建新项目
     */
    @Transactional
    public String createProject(String userId, String storyPrompt, String genre,
                               String targetAudience, Integer totalEpisodes,
                               Integer episodeDuration) {
        Project project = new Project();
        project.setProjectId(generateProjectId());
        project.setUserId(userId);
        project.setStoryPrompt(storyPrompt);
        project.setGenre(genre);
        project.setTargetAudience(targetAudience);
        project.setTotalEpisodes(totalEpisodes);
        project.setEpisodeDuration(episodeDuration);
        project.setStatus("DRAFT");

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

    // ================= 私有方法 =================

    private String calculateNextStatus(String currentStatus, String event) {
        // 根据事件确定下一状态
        switch (event) {
            case "start_script_generation":
                return "SCRIPT_GENERATING";
            case "script_generated":
                return "SCRIPT_REVIEW";
            case "script_confirmed":
                return "SCRIPT_CONFIRMED";
            case "script_revision_requested":
                return "SCRIPT_REVISION_REQUESTED";
            case "start_character_extraction":
                return "CHARACTER_EXTRACTING";
            case "characters_extracted":
                return "CHARACTER_REVIEW";
            case "characters_confirmed":
                return "CHARACTER_CONFIRMED";
            case "start_image_generation":
                return "IMAGE_GENERATING";
            case "images_generated":
                return "IMAGE_REVIEW";
            case "image_confirmed":
                return "ASSET_LOCKED";
            case "start_production":
                return "PRODUCING";
            case "production_completed":
                return "COMPLETED";
            default:
                return null;
        }
    }

    private void triggerNextStage(String projectId, String status) {
        switch (status) {
            case "SCRIPT_GENERATING":
                // 触发剧本生成
                scriptService.generateScript(projectId);
                break;

            case "CHARACTER_EXTRACTING":
                // 触发角色提取
                characterExtractService.extractCharacters(projectId);
                break;

            // 其他状态的触发逻辑可以在这里添加
            case "IMAGE_GENERATING":
                // TODO: 触发图像生成
                log.info("图像生成功能待实现: projectId={}", projectId);
                break;

            case "PRODUCING":
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
