package com.comic.e2e;

import com.comic.ai.text.DeepSeekTextService;
import com.comic.service.production.StoryboardAgentService;
import com.comic.service.production.StoryboardLabelTemplateFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real API regression test for single-plan label template output.
 */
@Slf4j
class StoryboardLabelTemplateRealTest {

    private static final int TARGET_DURATION = 60;
    private static final String VISUAL_STYLE = "cinematic";
    private static final boolean COMIC_MODE = true;
    private static final String NARRATION_PERSPECTIVE = "third_person";
    private static final String SCRIPT_STYLE = "standard";

    private static final String LABEL_SCENE = "\uFF08\u573A\u666F\uFF09";
    private static final String LABEL_AUDIO = "\uFF08\u97F3\u6548\uFF09";

    private static StoryboardAgentService service;

    private static final String SAMPLE_SCRIPT =
            "A hacker discovers a hidden server room and notices abnormal indicator lights.\n"
                    + "She realizes this server hosts the core of a super AI.\n"
                    + "The AI project owner appears and the conflict escalates.\n"
                    + "At the key moment, evidence is synced to an external network.";

    @BeforeAll
    static void setUp() throws Exception {
        String apiKey = readApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("Please set DEEPSEEK_API_KEY via env or .env");
        }
        log.info("API Key: {}...{}", apiKey.substring(0, 4), apiKey.substring(apiKey.length() - 4));

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        ObjectMapper objectMapper = new ObjectMapper();

        DeepSeekTextService executor = new DeepSeekTextService(client, objectMapper);
        setField(executor, "apiKey", apiKey);
        setField(executor, "baseUrl", "https://api.deepseek.com");
        setField(executor, "model", "deepseek-v4-flash");
        setField(executor, "maxTokens", 8192);

        DeepSeekTextService reasoner = new DeepSeekTextService(client, objectMapper);
        setField(reasoner, "apiKey", apiKey);
        setField(reasoner, "baseUrl", "https://api.deepseek.com");
        setField(reasoner, "model", "deepseek-v4-flash");
        setField(reasoner, "maxTokens", 4096);

        service = new StoryboardAgentService(reasoner, executor, objectMapper);
    }

    @Test
    @DisplayName("Real API: single-plan label template regression")
    void testLabelTemplateSinglePlan_realApi() throws Exception {
        String modeName = COMIC_MODE ? "commentary" : "normal";
        File outputDir = new File("target");
        if (!outputDir.exists()) {
            assertTrue(outputDir.mkdirs(), "failed to create target output directory");
        }
        File outputFile = new File(outputDir, "storyboard-label-" + modeName + "-" + TARGET_DURATION + "s.txt");

        PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile), "UTF-8"));
        try {
            out.println("==============================================");
            out.println("  Storyboard Label Single-Plan Regression");
            out.println("==============================================");
            out.println("Mode: " + modeName);
            out.println("Duration: " + TARGET_DURATION + "s");
            out.println("Visual style: " + VISUAL_STYLE);
            out.println();

            out.println("Generating...\n");
            out.flush();

            List<Map<String, Object>> shots = service.generate(
                    SAMPLE_SCRIPT,
                    "Lin Xiaoxing, Chen Mo",
                    TARGET_DURATION,
                    VISUAL_STYLE,
                    COMIC_MODE,
                    NARRATION_PERSPECTIVE,
                    SCRIPT_STYLE
            );

            assertFalse(shots.isEmpty(), "shots should not be empty");

            out.println("Shot count: " + shots.size());
            out.println();

            for (Map<String, Object> shot : shots) {
                int shotNo = toInt(shot.get("globalShotNumber"), toInt(shot.get("shotNumber"), 0));
                int duration = toInt(shot.get("duration"), 0);
                String tagged = StoryboardLabelTemplateFormatter.formatShot(shot);

                assertTrue(tagged.contains(LABEL_SCENE),
                        "Shot #" + shotNo + " formatted text must contain " + LABEL_SCENE);
                assertTrue(tagged.contains(LABEL_AUDIO),
                        "Shot #" + shotNo + " formatted text must contain " + LABEL_AUDIO);

                out.println("--------------------------------------------------");
                out.println("Shot #" + shotNo + " [" + duration + "s]");
                out.println("--------------------------------------------------");
                out.println("[Label Template Text - Single Plan]");
                out.println(tagged);
                out.println();
            }
        } finally {
            out.flush();
            out.close();
        }

        System.out.println("Result written to: " + outputFile.getPath());
    }

    private static int toInt(Object o, int def) {
        return o instanceof Number ? ((Number) o).intValue() : def;
    }

    private static String readApiKey() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey != null && !apiKey.isEmpty()) return apiKey;
        try {
            File envFile = new File(".env");
            if (!envFile.exists()) envFile = new File("../.env");
            if (!envFile.exists()) return null;
            for (String line : java.nio.file.Files.readAllLines(envFile.toPath())) {
                line = line.trim();
                if (line.startsWith("DEEPSEEK_API_KEY=")) {
                    String val = line.substring("DEEPSEEK_API_KEY=".length()).trim();
                    if (val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    }
                    return val;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
