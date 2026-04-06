package com.comic.e2e;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 原 E2E 依赖已删除的 {@code PipelineService}、{@code ProjectStatus} 等类型。
 * 需要时按 {@link com.comic.statemachine.enums.ProjectMilestone} 与当前 Controller/Service 重写。
 */
@Disabled("Obsolete suite removed from compilation path; rewrite for milestone state machine when needed")
class ProjectStateMachineE2ETest {

    @Test
    void placeholder() {
        // suite disabled at class level
    }
}
