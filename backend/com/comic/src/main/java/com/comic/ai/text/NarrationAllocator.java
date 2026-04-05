package com.comic.ai.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 旁白分配器
 * 将集级别旁白稿按 shot 约束切分分配到各旁白 shot
 *
 * 三层兜底策略：
 * 1. 在自然标点处切分
 * 2. 柔性字数区间匹配（允许 ±20% 超出）
 * 3. 降级：复用最后一句 / 截断 / 静音
 */
@Slf4j
@Component
public class NarrationAllocator {

    // 自然断句标点（按优先级排序）
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？]");
    private static final Pattern SENTENCE_PAUSE = Pattern.compile("[，、；……]");

    // 每个 duration 对应的字数区间 [min, max]
    private static final Map<Integer, int[]> WORD_COUNT_RANGE;
    static {
        Map<Integer, int[]> m = new HashMap<>();
        m.put(2, new int[]{5, 9});
        m.put(3, new int[]{9, 13});
        m.put(4, new int[]{12, 16});
        WORD_COUNT_RANGE = Collections.unmodifiableMap(m);
    }

    // 允许超出上限的百分比
    private static final float SOFT_MAX_BONUS = 0.20f;

    /**
     * 将旁白稿分配到 shots
     */
    public List<Map<String, Object>> allocate(String narrationDraft,
                                              List<Map<String, Object>> shots,
                                              String narrationPerspective) {
        if (narrationDraft == null || narrationDraft.isEmpty()) {
            log.warn("旁白稿为空，跳过分配，所有旁白 shot 设为「无」");
            markAllNarrationAsNone(shots);
            return shots;
        }

        List<String> sentences = splitSentences(narrationDraft);
        if (sentences.isEmpty()) {
            log.warn("旁白稿无法切分句子，跳过分配");
            markAllNarrationAsNone(shots);
            return shots;
        }

        log.info("旁白稿切分得到 {} 个句子", sentences.size());

        List<Map<String, Object>> narrationShots = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            if (!isDialogueShot(shot)) {
                narrationShots.add(shot);
            }
        }

        log.info("旁白 shot: {} 个", narrationShots.size());

        List<int[]> constraints = new ArrayList<>();
        for (Map<String, Object> shot : narrationShots) {
            int duration = safeInt(shot.get("duration"), 3);
            int[] range = WORD_COUNT_RANGE.getOrDefault(duration, new int[]{5, 13});
            int softMax = (int) Math.ceil(range[1] * (1 + SOFT_MAX_BONUS));
            constraints.add(new int[]{range[0], softMax});
        }

        int sentenceIdx = 0;

        for (int i = 0; i < narrationShots.size(); i++) {
            Map<String, Object> shot = narrationShots.get(i);
            int[] constraint = constraints.get(i);

            String allocated = tryAllocateSentences(
                    sentences, sentenceIdx, constraint, i + 1);

            shot.put("narration", allocated);
            if (allocated != null && !allocated.isEmpty()) {
                sentenceIdx += countSentencesInAllocation(allocated, sentences, sentenceIdx);
            }
        }

        enforceMutualExclusion(shots);

        long filledCount = narrationShots.stream()
                .filter(s -> !"无".equals(s.get("narration")))
                .count();
        log.info("旁白分配完成: 填充 {}/{} 个旁白 shot", filledCount, narrationShots.size());

        return shots;
    }

    private String tryAllocateSentences(List<String> sentences, int startIdx,
                                        int[] constraint, int shotNum) {
        int min = constraint[0];
        int softMax = constraint[1];

        for (int count = 1; count <= 3 && startIdx + count <= sentences.size(); count++) {
            StringBuilder combined = new StringBuilder();
            for (int j = startIdx; j < startIdx + count; j++) {
                if (combined.length() > 0) combined.append("，");
                combined.append(sentences.get(j));
            }
            int len = combined.length();

            if (len >= min && len <= softMax) {
                return combined.toString();
            }
            int hardMax = (int) ((double) softMax / (1 + SOFT_MAX_BONUS) * (1 + SOFT_MAX_BONUS * 1.5));
            if (len > softMax && len <= hardMax) {
                log.debug("Shot {} 字数 {} 超出软上限 {} 但在硬上限内，予以通过", shotNum, len, softMax);
                return combined.toString();
            }
        }

        if (startIdx < sentences.size()) {
            String last = sentences.get(sentences.size() - 1);
            int len = last.length();
            if (len > 0 && len <= softMax * 1.5) {
                log.warn("Shot {} 找不到合适句子组合，降级使用最后一句 ({}字)", shotNum, len);
                return last;
            }
            if (len > softMax * 1.5) {
                String truncated = truncateToLength(last, softMax);
                log.warn("Shot {} 最后一句截断为 {} 字", shotNum, truncated.length());
                return truncated;
            }
        }

        log.warn("Shot {} 无法分配旁白，设为「无」（静音）", shotNum);
        return "无";
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (SENTENCE_END.matcher(String.valueOf(c)).find()) {
                String s = current.toString().trim();
                if (!s.isEmpty()) sentences.add(s);
                current = new StringBuilder();
            }
        }

        String remaining = current.toString().trim();
        if (!remaining.isEmpty()) {
            if (remaining.length() > 20) {
                int mid = remaining.length() / 2;
                int cut = -1;
                for (int i = mid; i < remaining.length(); i++) {
                    if (SENTENCE_PAUSE.matcher(String.valueOf(remaining.charAt(i))).find()) {
                        cut = i + 1;
                        break;
                    }
                }
                if (cut > 0) {
                    sentences.add(remaining.substring(0, cut).trim());
                    sentences.add(remaining.substring(cut).trim());
                } else {
                    sentences.add(remaining);
                }
            } else {
                sentences.add(remaining);
            }
        }

        return sentences;
    }

    private void enforceMutualExclusion(List<Map<String, Object>> shots) {
        for (Map<String, Object> shot : shots) {
            String dialogue = str(shot.get("dialogue"));
            if (!"无".equals(dialogue) && !dialogue.isEmpty()) {
                String narration = str(shot.get("narration"));
                if (!"无".equals(narration) && !narration.isEmpty()) {
                    log.warn("Shot {} 违反互斥原则，清除 narration 保留 dialogue", shot.get("shotNumber"));
                    shot.put("narration", "无");
                }
            }
        }
    }

    private static final Pattern NARRATION_IN_DIALOGUE = Pattern.compile("旁白|解说|内心OS|（旁白）|（解说）");

    public void sanitizeDialogue(List<Map<String, Object>> shots) {
        for (Map<String, Object> shot : shots) {
            String dialogue = str(shot.get("dialogue"));
            if (!"无".equals(dialogue) && NARRATION_IN_DIALOGUE.matcher(dialogue).find()) {
                log.warn("Shot {} dialogue 包含旁白关键词，已清除: {}", shot.get("shotNumber"), dialogue);
                shot.put("dialogue", "无");
                shot.put("speaker", "无");
            }
            if (dialogue.trim().isEmpty()) {
                shot.put("dialogue", "无");
            }
            if ("无".equals(str(shot.get("dialogue")))) {
                String speaker = str(shot.get("speaker"));
                if (speaker.trim().isEmpty() || "旁白".equals(speaker)) {
                    shot.put("speaker", "无");
                }
            }
        }
    }

    public void forceDialogueRatio(List<Map<String, Object>> shots, int target) {
        List<Map<String, Object>> dialogueShots = new ArrayList<>();
        for (Map<String, Object> shot : shots) {
            if (!"无".equals(str(shot.get("dialogue")))) {
                dialogueShots.add(shot);
            }
        }

        int excess = dialogueShots.size() - target;
        if (excess <= 0) return;

        Pattern strongEmotion = Pattern.compile("怒|喊|哭|大笑|嘶吼|咆哮|狂笑|怒吼");
        dialogueShots.sort((a, b) -> {
            boolean ea = strongEmotion.matcher(str(a.get("dialogueTone"))).find();
            boolean eb = strongEmotion.matcher(str(b.get("dialogueTone"))).find();
            return Boolean.compare(eb, ea);
        });

        for (int i = target; i < dialogueShots.size(); i++) {
            log.warn("Shot {} 对白比例超限，清除 dialogue: {}",
                    dialogueShots.get(i).get("shotNumber"), dialogueShots.get(i).get("dialogue"));
            dialogueShots.get(i).put("dialogue", "无");
            dialogueShots.get(i).put("speaker", "无");
            dialogueShots.get(i).put("dialogueTone", "无");
        }
    }

    private boolean isDialogueShot(Map<String, Object> shot) {
        return !"无".equals(str(shot.get("dialogue"))) && !str(shot.get("dialogue")).isEmpty();
    }

    private void markAllNarrationAsNone(List<Map<String, Object>> shots) {
        for (Map<String, Object> shot : shots) {
            if (!isDialogueShot(shot)) {
                shot.put("narration", "无");
            }
        }
    }

    private String truncateToLength(String text, int maxLen) {
        for (int i = Math.min(text.length() - 1, maxLen); i >= maxLen / 2; i--) {
            if (SENTENCE_PAUSE.matcher(String.valueOf(text.charAt(i))).find()) {
                return text.substring(0, i + 1).trim();
            }
        }
        return text.substring(0, maxLen).trim() + "……";
    }

    private int countSentencesInAllocation(String allocated, List<String> sentences, int startIdx) {
        int count = 0;
        String normalized = allocated.replace("，", "").trim();
        for (int i = startIdx; i < sentences.size(); i++) {
            String s = sentences.get(i).replace("，", "").trim();
            if (normalized.startsWith(s)) {
                count++;
                normalized = normalized.substring(s.length());
                if (normalized.isEmpty()) break;
            } else {
                break;
            }
        }
        return Math.max(1, count);
    }

    private String str(Object o) {
        return o != null ? o.toString().trim() : "";
    }

    private int safeInt(Object o, int defaultVal) {
        if (o == null) return defaultVal;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return (int) Double.parseDouble(o.toString().trim()); }
        catch (Exception e) { return defaultVal; }
    }
}