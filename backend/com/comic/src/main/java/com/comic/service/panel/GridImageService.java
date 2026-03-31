package com.comic.service.panel;

import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.image.SeedreamImageService;
import com.comic.common.BusinessException;
import com.comic.common.CharacterInfoKeys;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.service.storyboard.StoryboardService;
import com.comic.util.NumberFormatter;
import com.comic.service.oss.OssService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class GridImageService {

    private static final int GRID_COLS = 3;
    private static final int GRID_ROWS = 3;
    private static final int SHOTS_PER_PAGE = GRID_COLS * GRID_ROWS;
    private static final int GRID_SEPARATOR_PIXELS = 4;
    private static final Color FUSION_BG_COLOR = new Color(0x1a, 0x1a, 0x1c);

    @Resource private SeedreamImageService seedreamImageService;
    @Resource private PanelPromptBuilder panelPromptBuilder;
    @Resource private PanelRepository panelRepository;
    @Resource private OssService ossService;
    @Resource private EpisodeRepository episodeRepository;
    @Resource private CharacterRepository characterRepository;

    /**
     * 为指定 Panel 生成九宫格图 → 切割 → 融合参考图
     */
    public void generateGridsForPanel(Long panelId) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在: " + panelId);

        Map<String, Object> panelInfo = panel.getPanelInfo();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) panelInfo.get("shots");
        String visualStyleStr = (String) panelInfo.getOrDefault("visualStyle", "ANIME");

        try {
            panelInfo.put("gridStatus", "generating");
            updatePanelInfo(panel, panelInfo);

            List<String> characterRefUrls = getCharacterReferenceUrls(panel.getEpisodeId());
            int pageCount = calculatePageCount(shots.size(), SHOTS_PER_PAGE);
            List<String> gridImageUrls = new ArrayList<>();

            for (int page = 0; page < pageCount; page++) {
                int fromIdx = page * SHOTS_PER_PAGE;
                int toIdx = Math.min(fromIdx + SHOTS_PER_PAGE, shots.size());
                List<Map<String, Object>> pageShots = shots.subList(fromIdx, toIdx);

                String prompt = panelPromptBuilder.buildGridPrompt(visualStyleStr, pageShots, characterRefUrls);
                String imageUrl;
                if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
                    imageUrl = seedreamImageService.generateWithMultipleReferences(
                        prompt, characterRefUrls, 1920, 1080);
                } else {
                    imageUrl = seedreamImageService.generate(prompt, 1920, 1080, visualStyleStr);
                }
                gridImageUrls.add(imageUrl);
            }

            // 切割九宫格
            for (int page = 0; page < gridImageUrls.size(); page++) {
                BufferedImage gridImage = downloadImage(gridImageUrls.get(page));
                List<BufferedImage> subImages = splitGridImage(gridImage, GRID_COLS, GRID_ROWS);
                int fromIdx = page * SHOTS_PER_PAGE;
                for (int i = 0; i < subImages.size() && (fromIdx + i) < shots.size(); i++) {
                    String ossUrl = uploadToOss(subImages.get(i), panelId, fromIdx + i);
                    // 创建副本并更新 splitImageUrl，避免修改原始 shots
                    @SuppressWarnings("unchecked")
                    Map<String, Object> shotCopy = new HashMap<>(shots.get(fromIdx + i));
                    shotCopy.put("splitImageUrl", ossUrl);
                    shots.set(fromIdx + i, shotCopy);
                }
            }

            // 融合参考图
            BufferedImage fusionImage = createFusionImage(shots, characterRefUrls);
            panelInfo.put("fusionImageUrl", uploadToOss(fusionImage, panelId, "fusion"));
            panelInfo.put("gridImages", gridImageUrls);
            panelInfo.put("gridStatus", "generated");
            panelInfo.put("gridPageCount", pageCount);
            updatePanelInfo(panel, panelInfo);

            log.info("Panel {} 九宫格完成, {} 页, {} 分镜", panelId, pageCount, shots.size());

        } catch (Exception e) {
            log.error("Panel {} 九宫格失败", panelId, e);
            panelInfo.put("gridStatus", "failed");
            panelInfo.put("errorMessage", e.getMessage());
            updatePanelInfo(panel, panelInfo);
        }
    }

    /**
     * 更新 Episode 的九宫格状态
     */
    public void updateEpisodeGridStatus(Long episodeId, String status) {
        Episode episode = episodeRepository.selectById(episodeId);
        if (episode == null) throw new BusinessException("Episode 不存在: " + episodeId);
        Map<String, Object> info = episode.getEpisodeInfo() != null ? episode.getEpisodeInfo() : new HashMap<>();
        info.put("gridStatus", status);
        episode.setEpisodeInfo(info);
        episodeRepository.updateById(episode);
    }

    /**
     * 为整个 Episode 生成九宫格图 → 切割 → 构建 splitShots
     * 与 generateGridsForPanel 逻辑类似，但操作的是 episodeInfo 而非 panelInfo
     */
    @Async
    public void generateGridsForEpisode(Long episodeId, List<Map<String, Object>> shots, String visualStyle) {
        try {
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode == null) throw new BusinessException("Episode 不存在: " + episodeId);

            Map<String, Object> episodeInfo = episode.getEpisodeInfo();
            List<String> characterRefUrls = getCharacterReferenceUrls(episodeId);
            int pageCount = calculatePageCount(shots.size(), SHOTS_PER_PAGE);
            List<String> gridImageUrls = new ArrayList<>();

            // 逐页生成九宫格
            for (int page = 0; page < pageCount; page++) {
                int fromIdx = page * SHOTS_PER_PAGE;
                int toIdx = Math.min(fromIdx + SHOTS_PER_PAGE, shots.size());
                List<Map<String, Object>> pageShots = shots.subList(fromIdx, toIdx);

                String prompt = panelPromptBuilder.buildGridPrompt(visualStyle, pageShots, characterRefUrls);
                String imageUrl;
                if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
                    imageUrl = seedreamImageService.generateWithMultipleReferences(
                        prompt, characterRefUrls, 1920, 1080);
                } else {
                    imageUrl = seedreamImageService.generate(prompt, 1920, 1080, visualStyle);
                }
                gridImageUrls.add(imageUrl);
            }

            // 切割九宫格 → 构建 splitShots
            List<Map<String, Object>> splitShots = new ArrayList<>();
            for (int page = 0; page < gridImageUrls.size(); page++) {
                BufferedImage gridImage = downloadImage(gridImageUrls.get(page));
                List<BufferedImage> subImages = splitGridImage(gridImage, GRID_COLS, GRID_ROWS);
                int fromIdx = page * SHOTS_PER_PAGE;
                for (int i = 0; i < subImages.size() && (fromIdx + i) < shots.size(); i++) {
                    Map<String, Object> shot = shots.get(fromIdx + i);
                    String ossUrl = uploadToOssEpisode(subImages.get(i), episodeId, fromIdx + i);
                    Map<String, Object> splitShot = new HashMap<>(shot);
                    splitShot.put("splitImageUrl", ossUrl);
                    splitShots.add(splitShot);
                }
            }

            // 更新 episodeInfo
            episodeInfo.put("gridImages", gridImageUrls);
            episodeInfo.put("splitShots", splitShots);
            episodeInfo.put("gridStatus", "generated");
            episodeInfo.put("gridPageCount", pageCount);
            episode.setEpisodeInfo(episodeInfo);
            episodeRepository.updateById(episode);

            log.info("Episode {} 整集九宫格完成, {} 页, {} 分镜", episodeId, pageCount, shots.size());

        } catch (Exception e) {
            log.error("Episode {} 整集九宫格失败", episodeId, e);
            try {
                updateEpisodeGridStatus(episodeId, "failed");
                Episode episode = episodeRepository.selectById(episodeId);
                if (episode != null) {
                    Map<String, Object> info = episode.getEpisodeInfo();
                    info.put("errorMessage", e.getMessage());
                    episode.setEpisodeInfo(info);
                    episodeRepository.updateById(episode);
                }
            } catch (Exception ex) {
                log.error("更新失败状态异常: episodeId={}", episodeId, ex);
            }
        }
    }

    /**
     * 切割九宫格（纯函数）
     * 考虑分隔线像素：将 GRID_SEPARATOR_PIXELS 从格子间扣除
     */
    public static List<BufferedImage> splitGridImage(BufferedImage img, int cols, int rows) {
        int sep = GRID_SEPARATOR_PIXELS;
        List<BufferedImage> subImages = new ArrayList<>();

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int cellW = (img.getWidth() - (cols - 1) * sep) / cols;
                int cellH = (img.getHeight() - (rows - 1) * sep) / rows;
                int x = c * (cellW + sep);
                int y = r * (cellH + sep);
                int w = cellW;
                int h = cellH;

                // 最后一列/行取剩余像素，避免累积偏差
                if (c == cols - 1) {
                    w = img.getWidth() - x;
                }
                if (r == rows - 1) {
                    h = img.getHeight() - y;
                }

                subImages.add(img.getSubimage(x, y, w, h));
            }
        }
        return subImages;
    }

    /** 分页数（纯函数） */
    public static int calculatePageCount(int totalShots, int shotsPerPage) {
        return totalShots <= 0 ? 0 : (int) Math.ceil((double) totalShots / shotsPerPage);
    }

    /**
     * 为指定 Panel 的 splitShots 创建融合参考图（公开版）
     */
    public BufferedImage createFusionImageForPanel(List<Map<String, Object>> panelShots, List<String> charRefUrls) {
        return createFusionImage(panelShots, charRefUrls);
    }

    /**
     * 获取 Episode 的角色参考图（公开版本，供外部调用）
     */
    public List<String> getCharacterReferenceUrlsForEpisode(Long episodeId) {
        return getCharacterReferenceUrls(episodeId);
    }

    /**
     * 上传 Panel 融合图到 OSS
     */
    public String uploadFusionImageForPanel(BufferedImage img, Long episodeId, List<Map<String, Object>> shots) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            byte[] bytes = baos.toByteArray();
            int firstShotNum = shots.isEmpty() ? 0 : ((Number) shots.get(0).get("shotNumber")).intValue();
            String fileName = "episode_" + episodeId + "_fusion_" + firstShotNum + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            String objectKey = "comic/grids/" + fileName;
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            return ossService.uploadFromInputStream(bais, objectKey, "image/png", bytes.length);
        } catch (Exception e) {
            throw new RuntimeException("融合图上传失败: episodeId=" + episodeId, e);
        }
    }

    private BufferedImage createFusionImage(List<Map<String, Object>> shots, List<String> charRefUrls) {
        // 固定输出尺寸：1920x1080 (16:9)
        final int FIXED_WIDTH = 1920;
        final int FIXED_HEIGHT = 1080;
        final int BOTTOM_BAR_HEIGHT = 180;  // 角色参考图底部横条高度
        final int MAIN_AREA_HEIGHT = FIXED_HEIGHT - BOTTOM_BAR_HEIGHT;
        final int PAD = 4;

        // 计算分镜网格布局
        int fCols = 3;
        int fRows = (int) Math.ceil((double) shots.size() / fCols);
        int cellW = (FIXED_WIDTH - PAD * (fCols + 1)) / fCols;
        int cellH = (MAIN_AREA_HEIGHT - PAD * (fRows + 1)) / fRows;

        // 创建画布
        BufferedImage canvas = new BufferedImage(FIXED_WIDTH, FIXED_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(FUSION_BG_COLOR);
        g.fillRect(0, 0, FIXED_WIDTH, FIXED_HEIGHT);

        // 绘制分镜网格
        for (int i = 0; i < shots.size(); i++) {
            int col = i % fCols;
            int row = i / fCols;
            int x = PAD + col * (cellW + PAD);
            int y = PAD + row * (cellH + PAD);
            String splitUrl = (String) shots.get(i).get("splitImageUrl");
            if (splitUrl != null) {
                try {
                    BufferedImage sub = downloadImage(splitUrl);
                    // 等比缩放居中绘制
                    double scale = Math.min((double) cellW / sub.getWidth(), (double) cellH / sub.getHeight());
                    int drawW = (int) (sub.getWidth() * scale);
                    int drawH = (int) (sub.getHeight() * scale);
                    int drawX = x + (cellW - drawW) / 2;
                    int drawY = y + (cellH - drawH) / 2;
                    g.drawImage(sub, drawX, drawY, drawW, drawH, null);
                } catch (Exception e) {
                    log.warn("融合图加载失败: shot {}", i);
                }
            }
            // 绘制编号
            g.setColor(Color.BLACK);
            g.fillRect(x + 2, y + 2, 24, 18);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            String label = NumberFormatter.toCircled(i + 1);
            g.drawString(label, x + 4, y + 15);
        }

        // 绘制底部角色参考图横条
        if (charRefUrls != null && !charRefUrls.isEmpty()) {
            int charCount = charRefUrls.size();
            int charW = (FIXED_WIDTH - PAD * (charCount + 1)) / charCount;
            int charH = BOTTOM_BAR_HEIGHT - PAD * 2;
            int charY = MAIN_AREA_HEIGHT + PAD;

            for (int i = 0; i < charRefUrls.size(); i++) {
                int charX = PAD + i * (charW + PAD);
                try {
                    BufferedImage charImg = downloadImage(charRefUrls.get(i));
                    // 等比缩放
                    double scale = Math.min((double) charW / charImg.getWidth(), (double) charH / charImg.getHeight());
                    int drawW = (int) (charImg.getWidth() * scale);
                    int drawH = (int) (charImg.getHeight() * scale);
                    int drawX = charX + (charW - drawW) / 2;
                    int drawY = charY + (charH - drawH) / 2;
                    g.drawImage(charImg, drawX, drawY, drawW, drawH, null);

                    // 角色编号
                    g.setColor(Color.BLACK);
                    g.fillRect(charX + 2, charY + 2, 20, 16);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("SansSerif", Font.PLAIN, 10));
                    g.drawString("C" + (i + 1), charX + 4, charY + 14);
                } catch (Exception e) {
                    log.warn("角色参考图加载失败: {}", charRefUrls.get(i));
                }
            }
        }

        g.dispose();
        return canvas;
    }

    private List<String> getCharacterReferenceUrls(Long episodeId) {
        List<String> urls = new ArrayList<>();
        try {
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode == null) {
                log.warn("角色参考图: episodeId={} 不存在", episodeId);
                return urls;
            }

            Map<String, Object> episodeInfo = episode.getEpisodeInfo();
            if (episodeInfo == null) {
                log.warn("角色参考图: episodeId={} episodeInfo 为空", episodeId);
                return urls;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shots = (List<Map<String, Object>>) episodeInfo.get("shots");
            if (shots == null) {
                log.warn("角色参考图: episodeId={} shots 为空", episodeId);
                return urls;
            }

            // 收集所有 charId（优先）和角色名（兜底）
            java.util.Set<String> charIds = new java.util.LinkedHashSet<>();
            java.util.Set<String> charNames = new java.util.LinkedHashSet<>();

            for (Map<String, Object> shot : shots) {
                // 优先从 characterRefs 获取 charId
                @SuppressWarnings("unchecked")
                List<Map<String, String>> charRefs = (List<Map<String, String>>) shot.get("characterRefs");
                if (charRefs != null) {
                    for (Map<String, String> ref : charRefs) {
                        String charId = ref.get("charId");
                        if (charId != null && !charId.isEmpty()) {
                            charIds.add(charId);
                        }
                        String name = ref.get("name");
                        if (name != null && !name.trim().isEmpty()) {
                            charNames.add(name.trim());
                        }
                    }
                } else {
                    // 兜底：从旧字段 characters 获取
                    @SuppressWarnings("unchecked")
                    List<String> characters = (List<String>) shot.get("characters");
                    if (characters != null) {
                        for (String c : characters) {
                            if (c != null && !c.trim().isEmpty()) {
                                charNames.add(c.trim());
                            }
                        }
                    }
                }
            }

            log.info("角色参考图: episodeId={} 收集到 charIds={}, charNames={}", episodeId, charIds, charNames);

            // 优先用 charId 匹配
            for (String charId : charIds) {
                Character ch = characterRepository.findByCharId(charId);
                if (ch == null) {
                    log.warn("角色参考图: episodeId={} charId '{}' 未找到", episodeId, charId);
                    continue;
                }
                String url = getCharacterImageUrl(ch);
                if (url != null && !url.isEmpty()) {
                    urls.add(url);
                    log.info("角色参考图: episodeId={} charId={} 匹配成功, url={}", episodeId, charId, url);
                }
            }

            // 兜底：用角色名匹配未通过 charId 找到的角色
            if (charNames.size() > charIds.size()) {
                List<Character> allChars = characterRepository.findByProjectId(episode.getProjectId());
                Map<String, Character> nameToChar = new HashMap<>();
                for (Character ch : allChars) {
                    Map<String, Object> info = ch.getCharacterInfo();
                    if (info != null) {
                        String name = (String) info.get(CharacterInfoKeys.NAME);
                        if (name != null) nameToChar.put(name.trim(), ch);
                    }
                }

                for (String charName : charNames) {
                    if (charIds.contains(charName)) continue; // 已处理
                    Character ch = nameToChar.get(charName);
                    if (ch == null) {
                        log.warn("角色参考图: episodeId={} 角色名 '{}' 未在数据库中找到匹配", episodeId, charName);
                        continue;
                    }
                    String url = getCharacterImageUrl(ch);
                    if (url != null && !url.isEmpty()) {
                        urls.add(url);
                        log.info("角色参考图: episodeId={} 角色名 '{}' 匹配成功", episodeId, charName);
                    }
                }
            }

            log.info("角色参考图: episodeId={} 最终URL数量={}", episodeId, urls.size());
        } catch (Exception e) {
            log.error("获取角色参考图失败: episodeId={}", episodeId, e);
        }
        return urls;
    }

    /**
     * 获取角色的参考图 URL（三视图或表情图）
     */
    private String getCharacterImageUrl(Character ch) {
        Map<String, Object> info = ch.getCharacterInfo();
        if (info == null) return null;

        String url = (String) info.get(CharacterInfoKeys.THREE_VIEW_GRID_URL);
        if (url == null || url.isEmpty()) {
            url = (String) info.get(CharacterInfoKeys.EXPRESSION_GRID_URL);
        }
        return url;
    }

    private BufferedImage downloadImage(String url) {
        try (InputStream is = new URL(url).openStream()) { return ImageIO.read(is); }
        catch (Exception e) { throw new RuntimeException("图片下载失败: " + url, e); }
    }

    private String uploadToOss(BufferedImage img, Long panelId, Object suffix) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            byte[] bytes = baos.toByteArray();
            String fileName = "panel_" + panelId + "_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            String objectKey = "comic/grids/" + fileName;
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            return ossService.uploadFromInputStream(bais, objectKey, "image/png", bytes.length);
        } catch (Exception e) {
            throw new RuntimeException("OSS上传失败: panelId=" + panelId + ", suffix=" + suffix, e);
        }
    }

    private void updatePanelInfo(Panel panel, Map<String, Object> info) {
        panel.setPanelInfo(info);
        panelRepository.updateById(panel);
    }

    private String uploadToOssEpisode(BufferedImage img, Long episodeId, Object suffix) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            byte[] bytes = baos.toByteArray();
            String fileName = "episode_" + episodeId + "_grid_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            String objectKey = "comic/grids/" + fileName;
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
            return ossService.uploadFromInputStream(bais, objectKey, "image/png", bytes.length);
        } catch (Exception e) {
            throw new RuntimeException("OSS上传失败: episodeId=" + episodeId + ", suffix=" + suffix, e);
        }
    }
}
