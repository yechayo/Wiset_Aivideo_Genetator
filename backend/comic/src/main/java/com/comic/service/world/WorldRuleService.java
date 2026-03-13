package com.comic.service.world;

import com.comic.dto.WorldConfigDTO;
import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/**
 * 世界观规则服务
 * 从项目配置动态读取，MVP阶段使用基础规则
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorldRuleService {

    private final ProjectRepository projectRepository;

    /**
     * 获取世界观配置
     * @param projectId 项目ID
     */
    @Cacheable(value = "worldConfig", key = "#projectId")
    public WorldConfigDTO getWorldConfig(String projectId) {
        // 从项目配置中读取
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            // 兼容旧版本，如果项目不存在则使用默认配置
            return getDefaultConfig(projectId);
        }

        WorldConfigDTO config = new WorldConfigDTO();

        // 根据项目类型生成基础世界观规则
        config.setSeriesName(generateSeriesName(project.getStoryPrompt()));
        config.setGenre(project.getGenre());
        config.setTargetAudience(project.getTargetAudience());
        config.setRules(generateGenreRules(project.getGenre()));

        return config;
    }

    /**
     * 生成系列名称（从故事提示词中提取）
     */
    private String generateSeriesName(String storyPrompt) {
        // MVP阶段：简单提取前几个字作为系列名
        // 实际应该让LLM从故事提示词中生成合适的名称
        if (storyPrompt != null && storyPrompt.length() > 0) {
            String[] words = storyPrompt.split("[，。\\s]+");
            if (words.length > 0) {
                return words[0].substring(0, Math.min(6, words[0].length()));
            }
        }
        return "未命名系列";
    }

    /**
     * 根据类型生成基础规则
     */
    private java.util.List<String> generateGenreRules(String genre) {
        // MVP阶段：根据类型返回预设规则
        // 实际应该让LLM根据故事提示词生成世界观规则

        if (genre == null) {
            genre = "";
        }

        if (genre.contains("玄幻") || genre.contains("修真") || genre.contains("仙侠")) {
            return Arrays.asList(
                "修炼体系分九阶：炼气、筑基、金丹、元婴、化神、合体、大乘、渡劫、仙人",
                "血咒一旦施加，施咒者死亡方可解除",
                "天墟秘境每百年开放一次，共十层，越深危险越大",
                "元气修炼者不可使用科技武器，否则经脉俱废",
                "天道有感应，突破境界时会有天劫降临"
            );
        } else if (genre.contains("都市") || genre.contains("异能")) {
            return Arrays.asList(
                "异能者需要向政府登记注册",
                "异能使用消耗体力，过度使用会昏迷",
                "异能可以分为五系：元素系、精神系、强化系、时空系、特殊系",
                "异能觉醒通常在16-25岁之间",
                "异能者之间可以发生异能共鸣，增强效果"
            );
        } else if (genre.contains("科幻") || genre.contains("机甲")) {
            return Arrays.asList(
                "人类已掌握曲速引擎技术",
                "机甲需要神经连接，驾驶员需要特殊天赋",
                "外星种族分为联盟、中立、敌对三大阵营",
                "能量矿石是宇宙通用货币",
                "AI拥有自主意识，与人类平等共存"
            );
        } else {
            // 默认规则
            return Arrays.asList(
                "世界规则待完善"
            );
        }
    }

    /**
     * 获取默认配置（兼容旧版本）
     */
    private WorldConfigDTO getDefaultConfig(String projectId) {
        WorldConfigDTO config = new WorldConfigDTO();
        config.setSeriesName("天墟传说");
        config.setGenre("热血玄幻");
        config.setTargetAudience("18-30岁男性");
        config.setRules(Arrays.asList(
            "修炼体系分九阶：炼气、筑基、金丹、元婴、化神、合体、大乘、渡劫、仙人",
            "血咒一旦施加，施咒者死亡方可解除",
            "天墟秘境每百年开放一次，共十层，越深危险越大",
            "元气修炼者不可使用科技武器，否则经脉俱废",
            "天道有感应，突破境界时会有天劫降临"
        ));
        return config;
    }
}
