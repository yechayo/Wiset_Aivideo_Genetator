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
    void should_resolve_production_completed_to_completed() {
        assertEquals(
            ProjectStatus.COMPLETED,
            ProjectStatus.resolveTransition(ProjectStatus.PRODUCING, "production_completed"),
            "Production completion should transition to COMPLETED state"
        );
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
    void should_resolve_all_panels_confirmed_to_producing() {
        assertEquals(
            ProjectStatus.PRODUCING,
            ProjectStatus.resolveTransition(ProjectStatus.PANEL_REVIEW, "all_panels_confirmed"),
            "Confirming all panels should transition to PRODUCING state"
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
