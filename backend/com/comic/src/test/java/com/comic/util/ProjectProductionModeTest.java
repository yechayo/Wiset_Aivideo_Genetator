package com.comic.util;

import com.comic.constant.ProjectInfoKeys;
import com.comic.entity.Project;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectProductionModeTest {

    @Test
    void isComicCommentary_true_when_project_info_set() {
        Map<String, Object> info = new HashMap<>();
        info.put(ProjectInfoKeys.PRODUCTION_MODE, "comic_commentary");
        Project p = new Project();
        p.setProjectInfo(info);
        assertTrue(ProjectProductionMode.isComicCommentary(p));
        assertTrue(ProjectProductionMode.isComicCommentary(info));
    }

    @Test
    void isComicCommentary_false_when_missing_or_realtime() {
        assertFalse(ProjectProductionMode.isComicCommentary((Project) null));
        Project p = new Project();
        assertFalse(ProjectProductionMode.isComicCommentary(p));
        Map<String, Object> info = new HashMap<>();
        info.put(ProjectInfoKeys.PRODUCTION_MODE, "realtime_animation");
        p.setProjectInfo(info);
        assertFalse(ProjectProductionMode.isComicCommentary(p));
    }
}
