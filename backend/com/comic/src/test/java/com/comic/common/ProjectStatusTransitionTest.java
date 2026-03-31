package com.comic.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test suite for ProjectStatus state transitions.
 *
 * This test documents the intended auto-chain behavior for state transitions.
 * Initially, some tests will FAIL because the current implementation doesn't
 * support automatic progression upon confirmation actions.
 *
 * These tests serve as a safety net - they document the desired behavior and
 * will initially fail, ensuring subsequent implementation actually achieves
 * the desired transitions.
 */
class ProjectStatusTransitionTest {

    @Test
    void should_resolve_confirm_images_to_asset_locked() {
        assertEquals(
            ProjectStatus.ASSET_LOCKED,
            ProjectStatus.resolveTransition(ProjectStatus.IMAGE_REVIEW, "confirm_images"),
            "Confirming images should transition to ASSET_LOCKED state"
        );
    }

    @Test
    void should_resolve_episode_script_generated_to_storyboard_generating() {
        assertEquals(ProjectStatus.STORYBOARD_GENERATING,
            ProjectStatus.resolveTransition(ProjectStatus.EPISODE_SCRIPT_GENERATING, "episode_script_generated"));
    }

    @Test
    void should_resolve_storyboard_generated_to_storyboard_review() {
        assertEquals(ProjectStatus.STORYBOARD_REVIEW,
            ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_GENERATING, "storyboard_generated"));
    }

    @Test
    void should_resolve_all_grids_approved_to_producing() {
        assertEquals(ProjectStatus.PRODUCING,
            ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_REVIEW, "all_grids_approved"));
    }

    @Test
    void should_resolve_production_completed_to_completed() {
        assertEquals(ProjectStatus.COMPLETED,
            ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "production_completed"));
    }

    @Test
    void should_resolve_episode_script_failed_to_failed_state() {
        assertEquals(ProjectStatus.EPISODE_SCRIPT_GENERATING_FAILED,
            ProjectStatus.resolveTransition(ProjectStatus.EPISODE_SCRIPT_GENERATING, "episode_script_failed"));
    }

    @Test
    void should_resolve_storyboard_failed_to_failed_state() {
        assertEquals(ProjectStatus.STORYBOARD_GENERATING_FAILED,
            ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_GENERATING, "storyboard_failed"));
    }

    @Test
    void should_resolve_storyboard_review_regenerate_to_episode_script() {
        assertEquals(ProjectStatus.EPISODE_SCRIPT_GENERATING,
            ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_REVIEW, "regenerate_storyboard"));
    }

    @Test
    void should_resolve_episode_script_failed_retry_to_itself() {
        assertEquals(ProjectStatus.EPISODE_SCRIPT_GENERATING,
            ProjectStatus.resolveTransition(ProjectStatus.EPISODE_SCRIPT_GENERATING_FAILED, "retry"));
    }

    @Test
    void should_resolve_storyboard_failed_retry_to_itself() {
        assertEquals(ProjectStatus.STORYBOARD_GENERATING,
            ProjectStatus.resolveTransition(ProjectStatus.STORYBOARD_GENERATING_FAILED, "retry"));
    }

    @Test
    void should_resolve_confirm_script_to_script_confirmed() {
        assertEquals(
            ProjectStatus.SCRIPT_CONFIRMED,
            ProjectStatus.resolveTransition(ProjectStatus.SCRIPT_REVIEW, "confirm_script"),
            "Confirming script should transition to SCRIPT_CONFIRMED state"
        );
    }

    @Test
    void should_resolve_confirm_characters_to_character_confirmed() {
        assertEquals(
            ProjectStatus.CHARACTER_CONFIRMED,
            ProjectStatus.resolveTransition(ProjectStatus.CHARACTER_REVIEW, "confirm_characters"),
            "Confirming characters should transition to CHARACTER_CONFIRMED state"
        );
    }

    @Test
    void should_reject_invalid_transition_from_image_review() {
        ProjectStatus result = ProjectStatus.resolveTransition(ProjectStatus.IMAGE_REVIEW, "invalid_event");
        assertEquals(
            null,
            result,
            "Invalid transitions should return null"
        );
    }

    @Test
    void should_reject_invalid_transition_from_producing() {
        ProjectStatus result = ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "invalid_event");
        assertEquals(
            null,
            result,
            "Invalid transitions should return null"
        );
    }
}
