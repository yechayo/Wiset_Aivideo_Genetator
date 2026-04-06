package com.comic.util;

import com.comic.constant.ProjectInfoKeys;
import com.comic.entity.Project;
import com.comic.enums.ProductionMode;

import java.util.Map;

/**
 * 从 {@link Project} / projectInfo 解析当前是否为漫剧解说制作模式。
 */
public final class ProjectProductionMode {

    private ProjectProductionMode() {}

    public static boolean isComicCommentary(Project project) {
        if (project == null) {
            return false;
        }
        return isComicCommentary(project.getProjectInfo());
    }

    public static boolean isComicCommentary(Map<String, Object> projectInfo) {
        if (projectInfo == null) {
            return false;
        }
        Object raw = projectInfo.get(ProjectInfoKeys.PRODUCTION_MODE);
        if (raw == null) {
            return false;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return false;
        }
        return ProductionMode.COMIC_COMMENTARY.getCode().equals(s);
    }
}
