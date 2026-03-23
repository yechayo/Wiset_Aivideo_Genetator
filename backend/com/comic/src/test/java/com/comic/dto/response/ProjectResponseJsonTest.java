package com.comic.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectResponseJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("ProjectStatusResponse serializes boolean fields with is* names")
    void projectStatusResponse_shouldSerializeWithIsPrefix() throws Exception {
        ProjectStatusResponse response = new ProjectStatusResponse();
        response.setGenerating(true);
        response.setFailed(false);
        response.setReview(true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertTrue(json.has("isGenerating"));
        assertTrue(json.has("isFailed"));
        assertTrue(json.has("isReview"));
        assertFalse(json.has("generating"));
        assertFalse(json.has("failed"));
        assertFalse(json.has("review"));
    }

    @Test
    @DisplayName("ProjectListItemResponse serializes boolean fields with is* names")
    void projectListItemResponse_shouldSerializeWithIsPrefix() throws Exception {
        ProjectListItemResponse response = new ProjectListItemResponse();
        response.setGenerating(true);
        response.setFailed(false);
        response.setReview(true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertTrue(json.has("isGenerating"));
        assertTrue(json.has("isFailed"));
        assertTrue(json.has("isReview"));
        assertFalse(json.has("generating"));
        assertFalse(json.has("failed"));
        assertFalse(json.has("review"));
    }
}