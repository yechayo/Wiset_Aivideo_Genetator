package com.comic.service.production;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class StoryboardTemplateAssembler {

    private static final String LABEL_SCENE = "\uFF08\u573A\u666F\uFF09";
    private static final String LABEL_CHARACTER = "\uFF08\u51FA\u573A\uFF09";
    private static final String JOINER = "\u3001";

    private StoryboardTemplateAssembler() {
    }

    public static String assembleVideoTemplate(List<String> scenes,
                                               List<String> characterNames,
                                               List<String> shotDescriptions) {
        List<String> lines = new ArrayList<>();
        lines.add(LABEL_SCENE + joinDistinctNonBlank(scenes));
        lines.add(LABEL_CHARACTER + joinDistinctNonBlank(characterNames));

        if (shotDescriptions != null) {
            for (String shotDescription : shotDescriptions) {
                String normalized = normalize(shotDescription);
                if (!normalized.isEmpty()) {
                    lines.add(normalized);
                }
            }
        }
        return String.join("\n", lines);
    }

    public static String assembleImageTemplate(String scene, String visualLabels) {
        List<String> lines = new ArrayList<>();
        lines.add(LABEL_SCENE + normalize(scene));

        String normalizedVisualLabels = normalize(visualLabels);
        if (!normalizedVisualLabels.isEmpty()) {
            lines.add(normalizedVisualLabels);
        }
        return String.join("\n", lines);
    }

    private static String joinDistinctNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        return String.join(JOINER, unique);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
