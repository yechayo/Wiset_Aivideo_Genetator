package com.comic.service.production;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storyboard 标签模板解析器。
 */
public final class StoryboardTemplateParser {

    private static final String LABEL_ACTION = "(动作)";
    private static final String LABEL_ENVIRONMENT = "(环境)";
    private static final String LABEL_STATE = "(状态)";
    private static final String LABEL_EXPRESSION = "(表情)";
    private static final String LABEL_AUDIO = "(音效)";
    private static final String LABEL_BLOCKING = "(走位)";
    private static final String LABEL_SCENE = "(场景)";
    private static final String LABEL_CHARACTER = "(出场)";
    private static final String LABEL_INNER_VOICE = "(内声)";
    private static final String INNER_VOICE_TEXT = LABEL_INNER_VOICE.substring(1, LABEL_INNER_VOICE.length() - 1);
    private static final Pattern INNER_VOICE_INLINE_PATTERN =
            Pattern.compile("^" + Pattern.quote(INNER_VOICE_TEXT) + "\\s*:(.*)$");
    private static final Pattern QUOTE_PATTERN =
            Pattern.compile("[\"\\u300c\\u201c]([^\"\\u300d\\u201d]*)[\"\\u300d\\u201d]");

    private StoryboardTemplateParser() {
    }

    public static String extractVisualLabels(String template) {
        if (isBlank(template)) {
            return "";
        }

        List<String> lines = splitLines(template);
        List<String> visualLines = new ArrayList<>();
        for (String line : lines) {
            String normalized = normalizeLine(line);
            if (startsWithVisualLabel(normalized)) {
                visualLines.add(line.trim());
            }
        }
        return String.join("\n", visualLines);
    }

    public static List<DialogueLine> extractDialogue(String template) {
        List<DialogueLine> result = new ArrayList<>();
        if (isBlank(template)) {
            return result;
        }

        for (String line : splitLines(template)) {
            String normalized = normalizeLine(line);
            if (!isInnerVoiceLine(normalized)) {
                continue;
            }

            String payload = stripLeadingDelimiter(extractInnerVoicePayload(normalized));
            if (isBlank(payload)) {
                continue;
            }

            if (payload.startsWith("(旁白)")) {
                String content = extractQuotedOrPlain(payload.substring("(旁白)".length()));
                if (!isBlank(content)) {
                    result.add(new DialogueLine("旁白", content, ""));
                }
                continue;
            }

            String quoted = extractQuotedOrPlain(payload);
            if (isBlank(quoted)) {
                continue;
            }

            int colon = quoted.indexOf(':');
            if (colon >= 0) {
                String speaker = quoted.substring(0, colon).trim();
                String content = quoted.substring(colon + 1).trim();
                if (!isBlank(content)) {
                    result.add(new DialogueLine(speaker, content, ""));
                }
            } else {
                result.add(new DialogueLine("", quoted, ""));
            }
        }

        return result;
    }

    public static List<String> extractAudioEffects(String template) {
        List<String> effects = new ArrayList<>();
        if (isBlank(template)) {
            return effects;
        }

        for (String line : splitLines(template)) {
            String normalized = normalizeLine(line);
            if (!normalized.startsWith(LABEL_AUDIO)) {
                continue;
            }
            String content = normalized.substring(LABEL_AUDIO.length()).trim();
            if (!content.isEmpty()) {
                effects.add(content);
            }
        }
        return effects;
    }

    public static List<BlockingInfo> extractBlocking(String template) {
        List<BlockingInfo> infos = new ArrayList<>();
        if (isBlank(template)) {
            return infos;
        }

        for (String line : splitLines(template)) {
            String normalized = normalizeLine(line);
            if (!normalized.startsWith(LABEL_BLOCKING)) {
                continue;
            }

            String payload = normalized.substring(LABEL_BLOCKING.length()).trim();
            if (payload.isEmpty()) {
                continue;
            }

            String[] segments = payload.split("\\|");
            String character = segments[0].trim();
            String positionLock = "";
            String poseLock = "";
            String facingLock = "";
            String propLock = "";

            for (int i = 1; i < segments.length; i++) {
                String segment = segments[i].trim();
                int eq = segment.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = segment.substring(0, eq).trim();
                String value = segment.substring(eq + 1).trim();
                if ("位置锁".equals(key)) {
                    positionLock = value;
                } else if ("姿态锁".equals(key)) {
                    poseLock = value;
                } else if ("朝向锁".equals(key)) {
                    facingLock = value;
                } else if ("道具锁".equals(key)) {
                    propLock = value;
                }
            }

            if (!character.isEmpty()) {
                infos.add(new BlockingInfo(character, positionLock, poseLock, facingLock, propLock));
            }
        }
        return infos;
    }

    public static boolean isTemplateText(String text) {
        if (isBlank(text)) {
            return false;
        }

        Set<String> hitLabels = new LinkedHashSet<>();
        for (String line : splitLines(text)) {
            String normalized = normalizeLine(line);
            String label = detectLabel(normalized);
            if (label != null) {
                hitLabels.add(label);
            }
        }
        return hitLabels.size() >= 2;
    }

    private static boolean startsWithVisualLabel(String normalizedLine) {
        return normalizedLine.startsWith(LABEL_ACTION)
                || normalizedLine.startsWith(LABEL_ENVIRONMENT)
                || normalizedLine.startsWith(LABEL_STATE)
                || normalizedLine.startsWith(LABEL_EXPRESSION);
    }

    private static String detectLabel(String normalizedLine) {
        if (isBlank(normalizedLine)) {
            return null;
        }

        if (normalizedLine.startsWith(LABEL_SCENE)) {
            return "场景";
        }
        if (normalizedLine.startsWith(LABEL_CHARACTER)) {
            return "出场";
        }
        if (normalizedLine.startsWith(LABEL_BLOCKING)) {
            return "走位";
        }
        if (normalizedLine.startsWith(LABEL_ACTION)) {
            return "动作";
        }
        if (normalizedLine.startsWith(LABEL_ENVIRONMENT)) {
            return "环境";
        }
        if (normalizedLine.startsWith(LABEL_STATE)) {
            return "状态";
        }
        if (normalizedLine.startsWith(LABEL_EXPRESSION)) {
            return "表情";
        }
        if (normalizedLine.startsWith(LABEL_AUDIO)) {
            return "音效";
        }
        if (isInnerVoiceLine(normalizedLine)) {
            return "内声";
        }
        return null;
    }

    private static boolean isInnerVoiceLine(String normalizedLine) {
        if (isBlank(normalizedLine)) {
            return false;
        }
        return normalizedLine.startsWith(LABEL_INNER_VOICE)
                || INNER_VOICE_INLINE_PATTERN.matcher(normalizedLine).matches();
    }

    private static String extractInnerVoicePayload(String normalizedLine) {
        if (normalizedLine.startsWith(LABEL_INNER_VOICE)) {
            return normalizedLine.substring(LABEL_INNER_VOICE.length()).trim();
        }
        Matcher matcher = INNER_VOICE_INLINE_PATTERN.matcher(normalizedLine);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static String stripLeadingDelimiter(String text) {
        String result = text == null ? "" : text.trim();
        while (result.startsWith(":")) {
            result = result.substring(1).trim();
        }
        return result;
    }

    private static String extractQuotedOrPlain(String text) {
        String normalized = text == null ? "" : text.trim();
        Matcher matcher = QUOTE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return normalized;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        String[] parts = text.split("\\R");
        for (String part : parts) {
            if (!isBlank(part)) {
                lines.add(part);
            }
        }
        return lines;
    }

    private static String normalizeLine(String line) {
        if (line == null) {
            return "";
        }
        return line.trim()
                .replace('（', '(')
                .replace('）', ')')
                .replace('｜', '|')
                .replace('：', ':')
                .replace('＝', '=')
                .replace('　', ' ');
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    public static final class DialogueLine {
        private final String speaker;
        private final String content;
        private final String tone;

        public DialogueLine(String speaker, String content, String tone) {
            this.speaker = speaker == null ? "" : speaker;
            this.content = content == null ? "" : content;
            this.tone = tone == null ? "" : tone;
        }

        public String getSpeaker() {
            return speaker;
        }

        public String getContent() {
            return content;
        }

        public String getTone() {
            return tone;
        }
    }

    public static final class BlockingInfo {
        private final String character;
        private final String positionLock;
        private final String poseLock;
        private final String facingLock;
        private final String propLock;

        public BlockingInfo(String character,
                            String positionLock,
                            String poseLock,
                            String facingLock,
                            String propLock) {
            this.character = character == null ? "" : character;
            this.positionLock = positionLock == null ? "" : positionLock;
            this.poseLock = poseLock == null ? "" : poseLock;
            this.facingLock = facingLock == null ? "" : facingLock;
            this.propLock = propLock == null ? "" : propLock;
        }

        public String getCharacter() {
            return character;
        }

        public String getPositionLock() {
            return positionLock;
        }

        public String getPoseLock() {
            return poseLock;
        }

        public String getFacingLock() {
            return facingLock;
        }

        public String getPropLock() {
            return propLock;
        }
    }
}
