package com.comic.service.world;

import com.comic.dto.model.WorldConfigModel;
import com.comic.entity.Project;
import com.comic.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;

/**
 * 世界观规则服务
 * 从项目配置动态读取，MVP阶段使用基础规则
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorldRuleService {

    private final ProjectRepository projectRepository;

    private String getProjectInfoStr(Project project, String key) {
        Map<String, Object> info = project.getProjectInfo();
        Object v = info != null ? info.get(key) : null;
        return v != null ? v.toString() : null;
    }

    /**
     * 获取世界观配置
     * @param projectId 项目ID
     */
    @Cacheable(value = "worldConfig", key = "#projectId")
    public WorldConfigModel getWorldConfig(String projectId) {
        // 从项目配置中读取
        Project project = projectRepository.findByProjectId(projectId);
        if (project == null) {
            // 兼容旧版本，如果项目不存在则使用默认配置
            return getDefaultConfig(projectId);
        }

        WorldConfigModel config = new WorldConfigModel();

        // 根据项目类型生成基础世界观规则
        config.setSeriesName(generateSeriesName(getProjectInfoStr(project, "storyPrompt")));
        config.setGenre(getProjectInfoStr(project, "genre"));
        config.setTargetAudience(getProjectInfoStr(project, "targetAudience"));
        config.setRules(generateGenreRules(getProjectInfoStr(project, "genre")));

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
     * 根据类型生成基础规则（仅提供大方向指引，不设定具体世界观细节）
     */
    private java.util.List<String> generateGenreRules(String genre) {
        if (genre == null) {
            genre = "";
        }

        if (genre.contains("玄幻") || genre.contains("修真") || genre.contains("仙侠")) {
            return Arrays.asList(
                "世界观以修炼、仙道为核心，力量体系需自洽",
                "剧情围绕成长与抗争展开，注重热血与冒险感",
                "允许神兽、法宝、秘境等经典元素，但需符合故事逻辑",
                "保持修炼等级体系的合理递进，避免战力崩坏"
            );
        } else if (genre.contains("都市") || genre.contains("异能")) {
            return Arrays.asList(
                "故事背景设定在现代都市，融入超自然或特殊能力元素",
                "异能/特殊能力的设定需合理且有边界，避免过于随意",
                "兼顾日常感与戏剧冲突，让超自然元素与普通生活产生反差",
                "角色需要在现实与超常之间寻找平衡"
            );
        } else if (genre.contains("科幻") || genre.contains("机甲")) {
            return Arrays.asList(
                "世界观建立在科学或科幻设定之上，科技逻辑需基本自洽",
                "允许大胆想象未来科技、太空探索或人工智能等主题",
                "注重科技设定对人类社会的深层影响",
                "剧情节奏紧凑，兼顾硬核设定与故事情感"
            );
        } else if (genre.contains("悬疑") || genre.contains("推理")) {
            return Arrays.asList(
                "剧情以悬念和谜题驱动，保持观众的好奇心",
                "关键线索需在揭晓前合理铺垫，反转需建立在已有信息之上",
                "每个角色都应有合理的动机和行为逻辑",
                "叙事节奏张弛有度，信息逐步揭示而非一次性抛出"
            );
        } else if (genre.contains("言情") || genre.contains("恋爱")) {
            return Arrays.asList(
                "以情感关系为核心驱动力，注重角色间的化学反应",
                "感情发展需有合理的节奏，避免突兀",
                "角色需有独立的人格和成长弧线，感情是角色成长的催化剂",
                "情感冲突应真实可信，能引发观众共鸣"
            );
        } else if (genre.contains("恐怖") || genre.contains("灵异")) {
            return Arrays.asList(
                "以恐惧和未知为核心体验，营造紧张氛围",
                "恐怖元素需有内在逻辑和规则，而非单纯堆砌吓人桥段",
                "角色面对恐惧时需有真实的心理反应和行为选择",
                "节奏由缓入急，层层递进"
            );
        } else if (genre.contains("青春") || genre.contains("校园")) {
            return Arrays.asList(
                "以青春成长为核心主题，围绕友情、梦想、自我认知展开",
                "角色性格鲜明且符合年龄段特征，成长弧线清晰",
                "冲突源于真实的生活场景，能引发观众共鸣",
                "整体基调积极向上，传递成长的力量"
            );
        } else if (genre.contains("搞笑") || genre.contains("日常")) {
            return Arrays.asList(
                "以轻松幽默为基调，注重笑点与节奏感",
                "角色互动有趣且有逻辑，搞笑不破坏人设",
                "在轻松氛围中可穿插温情或思考，增加层次感",
                "避免为搞笑而强行降智，保持故事的合理性"
            );
        } else {
            return Arrays.asList(
                "根据故事创意自主构建世界观，保持内在逻辑自洽",
                "注重角色塑造和剧情节奏，保持观众的参与感",
                "所有设定服务于故事本身，避免为设定而设定"
            );
        }
    }

    /**
     * 获取默认配置（兼容旧版本）
     */
    private WorldConfigModel getDefaultConfig(String projectId) {
        WorldConfigModel config = new WorldConfigModel();
        config.setSeriesName("天墟传说");
        config.setGenre("热血玄幻");
        config.setTargetAudience("18-30岁男性");
        config.setRules(Arrays.asList(
            "根据故事创意自主构建世界观，保持内在逻辑自洽",
            "注重角色塑造和剧情节奏，保持观众的参与感",
            "所有设定服务于故事本身，避免为设定而设定"
        ));
        return config;
    }
}
