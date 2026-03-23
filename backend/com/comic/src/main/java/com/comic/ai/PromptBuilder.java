package com.comic.ai;

import com.comic.dto.model.CharacterStateModel;
import com.comic.dto.model.WorldConfigModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt builder.
 */
@Component
public class PromptBuilder {

    public String buildStoryboardSystemPrompt(WorldConfigModel world, List<CharacterStateModel> charStates) {
        StringBuilder sb = new StringBuilder();
        String genre = world != null && world.getGenre() != null ? world.getGenre() : "unknown";
        String worldRules = world != null && world.getRulesText() != null ? world.getRulesText() : "";

        sb.append("You are a professional storyboard writer for ").append(genre).append(" comics.\n\n");
        sb.append("## World Rules\n").append(worldRules).append("\n\n");
        sb.append("## Character States\n");
        if (charStates != null) {
            for (CharacterStateModel state : charStates) {
                if (state != null) {
                    sb.append(state.toPromptText()).append("\n");
                }
            }
        }

        sb.append("\n## Output Contract (MUST follow exactly)\n");
        sb.append("Return JSON only. No markdown, no explanation.\n");
        sb.append("Top-level required fields: episode(number), title(string), panels(array).\n");
        sb.append("Each panel must include required fields: panel_id, shot_type, camera_angle, composition, ");
        sb.append("background(scene_desc,time_of_day,atmosphere), characters[], dialogue[], sfx[], pacing, image_prompt_hint.\n");
        sb.append("Allowed enums: shot_type={WIDE_SHOT,MID_SHOT,CLOSE_UP,OVER_SHOULDER}, ");
        sb.append("camera_angle={eye_level,low_angle,high_angle,bird_eye}, pacing={slow,normal,fast}, ");
        sb.append("bubble_type={speech,thought,narration_box}, costume_state={normal,battle_worn}.\n");
        return sb.toString();
    }

    public String buildEpisodeUserPrompt(int episodeNum, String outlineNode, String recentMemory) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate storyboard for episode ").append(episodeNum).append(".\n\n");
        sb.append("## Episode Outline\n").append(outlineNode != null ? outlineNode : "").append("\n\n");
        sb.append("## Recent Memory\n").append(recentMemory != null ? recentMemory : "").append("\n\n");
        sb.append("Requirements:\n");
        sb.append("- Keep narrative continuity\n");
        sb.append("- 6 to 8 panels\n");
        sb.append("- At least one high-tension panel\n");
        sb.append("- Follow system output contract strictly\n");
        sb.append("- Output JSON only\n");
        return sb.toString();
    }

    public String buildStoryboardRevisionUserPrompt(int episodeNum, String outlineNode, String recentMemory,
                                                    String currentStoryboardJson, String feedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("Revise storyboard for episode ").append(episodeNum).append(".\n\n");
        sb.append("## Episode Outline\n").append(outlineNode != null ? outlineNode : "").append("\n\n");
        sb.append("## Recent Memory\n").append(recentMemory != null ? recentMemory : "").append("\n\n");
        sb.append("## Current Storyboard\n").append(currentStoryboardJson != null ? currentStoryboardJson : "{}").append("\n\n");
        sb.append("## Feedback\n").append(feedback != null ? feedback : "").append("\n\n");
        sb.append("Requirements:\n");
        sb.append("- Apply incremental changes based on feedback\n");
        sb.append("- Keep continuity for unchanged parts\n");
        sb.append("- Follow system output contract strictly\n");
        sb.append("- Output full revised JSON only\n");
        return sb.toString();
    }

    public String buildOutlineSystemPrompt(String genre, String targetAudience) {
        return "You are an experienced comic writer for genre: " + genre
                + ". Target audience: " + targetAudience
                + ". Output must be valid JSON only.";
    }

    public String buildOutlineUserPrompt(String seriesName, String basicSetting, int episodesPerSeason) {
        return "Create a season outline for series '" + seriesName + "' with " + episodesPerSeason + " episodes.\n"
                + "Basic setting:\n" + basicSetting + "\n"
                + "Output JSON format: {\"season\":1,\"main_conflict\":\"...\",\"episodes\":[{\"ep\":1,\"title\":\"...\",\"plot_node\":\"...\",\"key_event\":\"...\"}]}";
    }

    public String buildScriptSystemPrompt(String genre, String worldRules, String targetAudience) {
        return "You are a professional comic script writer. Genre: " + genre
                + ", target audience: " + targetAudience
                + ". World rules:\n" + worldRules
                + "\nOutput must be valid JSON only.";
    }

    public String buildScriptUserPrompt(String storyPrompt, int totalEpisodes, int episodeDuration) {
        return "Based on the following story prompt, generate " + totalEpisodes + " episodes.\n"
                + "Story prompt:\n" + storyPrompt + "\n"
                + "Each episode duration: " + episodeDuration + " seconds.\n"
                + "Output JSON only.";
    }

    public String buildCharacterExtractSystemPrompt() {
        return "Extract major characters from script text. Output JSON array only: "
                + "[{\"name\":\"...\",\"role\":\"...\",\"personality\":\"...\",\"appearance\":\"...\",\"background\":\"...\"}]";
    }

    public String buildCharacterExtractUserPrompt(String scriptContent) {
        return "Extract main characters from this script:\n\n" + scriptContent + "\n\nOutput JSON array only.";
    }

    public String addStricterConstraints(String systemPrompt, int attempt) {
        if (attempt == 2) {
            return systemPrompt
                    + "\n\nIMPORTANT RETRY RULES (attempt 2):\n"
                    + "1. Return JSON only.\n"
                    + "2. Include all required top-level and panel fields.\n"
                    + "3. Keep enum values within the allowed set.";
        } else if (attempt >= 3) {
            return systemPrompt
                    + "\n\nFINAL WARNING (attempt 3):\n"
                    + "Output must be a single valid JSON object starting with '{' and ending with '}'.\n"
                    + "No extra text is allowed.";
        }
        return systemPrompt;
    }

    public ScriptParams calculateScriptParameters(int totalEpisodes) {
        if (totalEpisodes == 1) {
            int minCharacters = (int) Math.round(10 + (totalEpisodes * 0.15));
            int maxCharacters = (int) Math.round(minCharacters * 1.3);
            int minItems = (int) Math.round(8 + (totalEpisodes * 0.1));
            int maxItems = (int) Math.round(minItems * 1.25);
            return new ScriptParams(0, 0, minCharacters, maxCharacters, minItems, maxItems, true);
        }

        int episodesPerChapter;
        if (totalEpisodes <= 6) {
            episodesPerChapter = 2;
        } else if (totalEpisodes <= 10) {
            episodesPerChapter = 3;
        } else {
            episodesPerChapter = 4;
        }

        int chapterCount = (int) Math.ceil((double) totalEpisodes / episodesPerChapter);
        int minCharacters = (int) Math.round(10 + (totalEpisodes * 0.15));
        int maxCharacters = (int) Math.round(minCharacters * 1.3);
        int minItems = (int) Math.round(8 + (totalEpisodes * 0.1));
        int maxItems = (int) Math.round(minItems * 1.25);

        return new ScriptParams(chapterCount, episodesPerChapter, minCharacters, maxCharacters, minItems, maxItems, false);
    }

    public String buildScriptOutlineSystemPrompt(int totalEpisodes, String genre, String targetAudience) {
        ScriptParams params = calculateScriptParameters(totalEpisodes);
        if (params.isSingleEpisode) {
            return buildSingleEpisodePrompt(genre, params);
        }

        return "Create a chapter-level script outline in Chinese markdown.\n"
                + "Total episodes: " + totalEpisodes + "\n"
                + "Chapters: " + params.chapterCount + "\n"
                + "Episodes per chapter reference: " + params.episodesPerChapter + "\n"
                + "Target audience: " + targetAudience + "\n"
                + "Include characters, key items, and chapter arcs.";
    }

    public String buildScriptOutlineUserPrompt(String storyPrompt, String genre, String setting,
                                               int totalEpisodes, int episodeDuration, String visualStyle) {
        StringBuilder sb = new StringBuilder();
        sb.append("Core idea: ").append(storyPrompt).append("\n");
        sb.append("Genre: ").append(genre != null ? genre : "N/A").append("\n");
        sb.append("Setting: ").append(setting != null ? setting : "N/A").append("\n");
        sb.append("Episodes: ").append(totalEpisodes).append("\n");
        sb.append("Episode duration: ").append(episodeDuration).append(" minutes\n");
        sb.append("Visual style: ").append(visualStyle != null ? visualStyle : "N/A");
        return sb.toString();
    }

    public String buildScriptEpisodeSystemPrompt() {
        return "You are a professional episode script writer.\n"
                + "Given a global outline and one chapter, split it into concrete episode scripts.\n"
                + "Output JSON array only with fields: title, content, characters, keyItems, visualStyleNote, continuityNote.";
    }

    public String buildScriptEpisodeUserPrompt(String outline, String chapter, String globalCharacters,
                                               String globalItems, String previousSummary, int splitCount,
                                               int duration, String modificationSuggestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("Full outline:\n").append(outline).append("\n\n");
        sb.append("Target chapter: ").append(chapter).append("\n");
        sb.append("Split count: ").append(splitCount).append("\n");
        sb.append("Duration reference: ").append(duration).append(" minutes\n\n");
        if (modificationSuggestion != null && !modificationSuggestion.isEmpty()) {
            sb.append("Modification suggestion: ").append(modificationSuggestion).append("\n\n");
        }
        sb.append("Global characters:\n").append(globalCharacters).append("\n\n");
        sb.append("Global items:\n").append(globalItems).append("\n\n");
        sb.append("Previous episode summary:\n").append(previousSummary).append("\n");
        return sb.toString();
    }

    public static class ScriptParams {
        public final int chapterCount;
        public final int episodesPerChapter;
        public final int minCharacters;
        public final int maxCharacters;
        public final int minItems;
        public final int maxItems;
        public final boolean isSingleEpisode;

        public ScriptParams(int chapterCount, int episodesPerChapter, int minCharacters,
                            int maxCharacters, int minItems, int maxItems, boolean isSingleEpisode) {
            this.chapterCount = chapterCount;
            this.episodesPerChapter = episodesPerChapter;
            this.minCharacters = minCharacters;
            this.maxCharacters = maxCharacters;
            this.minItems = minItems;
            this.maxItems = maxItems;
            this.isSingleEpisode = isSingleEpisode;
        }

        public ScriptParams(int chapterCount, int episodesPerChapter, int minCharacters,
                            int maxCharacters, int minItems, int maxItems) {
            this(chapterCount, episodesPerChapter, minCharacters, maxCharacters, minItems, maxItems, false);
        }
    }

    private String buildSingleEpisodePrompt(String genre, ScriptParams params) {
        return "Create a full single-episode script outline in markdown.\n"
                + "Genre: " + genre + "\n"
                + "Character count: " + params.minCharacters + "-" + params.maxCharacters + "\n"
                + "Key items: " + params.minItems + "-" + params.maxItems + "\n"
                + "Include opening, development, climax, and ending.";
    }

    public static String[] getExpressionTypes() {
        return new String[]{"happy", "sad", "angry", "surprised", "fear", "disgust", "contempt", "shy", "calm"};
    }

    public static String[] getViewTypes() {
        return new String[]{"front", "side", "back"};
    }
}
