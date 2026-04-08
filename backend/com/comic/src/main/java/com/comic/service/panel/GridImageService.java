package com.comic.service.panel;

import com.comic.ai.ComicCommentaryPanelPromptBuilder;
import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.image.ImageGenerationService;
import com.comic.config.AiServiceConfiguration;
import com.comic.exception.BusinessException;
import com.comic.constant.CharacterInfoKeys;
import com.comic.entity.Character;
import com.comic.entity.Episode;
import com.comic.entity.Panel;
import com.comic.entity.Project;
import com.comic.repository.CharacterRepository;
import com.comic.repository.EpisodeRepository;
import com.comic.repository.PanelRepository;
import com.comic.repository.ProjectRepository;
import com.comic.util.ProjectProductionMode;
import com.comic.service.oss.OssService;
import com.comic.statemachine.service.StateChangeEventPublisher;
import com.comic.util.NumberFormatter;
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

    @Resource private AiServiceConfiguration aiServiceConfig;
    @Resource private PanelPromptBuilder panelPromptBuilder;
    @Resource private ComicCommentaryPanelPromptBuilder comicCommentaryPanelPromptBuilder;
    @Resource private PanelRepository panelRepository;
    @Resource private ProjectRepository projectRepository;
    @Resource private OssService ossService;
    @Resource private EpisodeRepository episodeRepository;
    @Resource private CharacterRepository characterRepository;
    @Resource private StateChangeEventPublisher eventPublisher;

    /**
     * 为指定 Panel 生成九宫格图 → 切割 → 融合参考图
     */
    public void generateGridsForPanel(Long panelId) {
        generateGridsForPanel(panelId, "seedream", null);
    }

    public void generateGridsForPanel(Long panelId, String imageProvider, String customHint) {
        Panel panel = panelRepository.selectById(panelId);
        if (panel == null) throw new BusinessException("Panel 不存在: " + panelId);

        Map<String, Object> panelInfo = panel.getPanelInfo();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) panelInfo.get("shots");
        String visualStyleStr = (String) panelInfo.getOrDefault("visualStyle", "ANIME");

        ImageGenerationService imageService = aiServiceConfig.getImageService(imageProvider != null ? imageProvider : "seedream");

        try {
            panelInfo.put("gridStatus", "generating");
            updatePanelInfo(panel, panelInfo);

            List<String> characterRefUrls = getCharacterReferenceUrls(panel.getEpisodeId());
            List<CharRef> charRefsWithNames = getCharacterReferencesWithNames(panel.getEpisodeId());
            int pageCount = calculatePageCount(shots.size(), SHOTS_PER_PAGE);
            List<String> gridImageUrls = new ArrayList<>();

            for (int page = 0; page < pageCount; page++) {
                int fromIdx = page * SHOTS_PER_PAGE;
                int toIdx = Math.min(fromIdx + SHOTS_PER_PAGE, shots.size());
                List<Map<String, Object>> pageShots = shots.subList(fromIdx, toIdx);

                String prompt = buildGridPromptForProject(panel.getEpisodeId(), visualStyleStr, pageShots, charRefsWithNames);
                prompt = appendUserHintToPrompt(prompt, customHint);
                String imageUrl;
                if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
                    imageUrl = imageService.generateWithMultipleReferences(
                        prompt, characterRefUrls, 1920, 1080);
                } else {
                    imageUrl = imageService.generate(prompt, 1920, 1080, visualStyleStr);
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
            BufferedImage fusionImage = createFusionImage(shots, charRefsWithNames);
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

    private Project resolveProjectByEpisodeId(Long episodeId) {
        if (episodeId == null) {
            return null;
        }
        Episode ep = episodeRepository.selectById(episodeId);
        if (ep == null || ep.getProjectId() == null) {
            return null;
        }
        return projectRepository.findByProjectId(ep.getProjectId());
    }

    private String buildGridPromptForProject(Long episodeId, String visualStyle,
                                             List<Map<String, Object>> pageShots, List<CharRef> charRefsWithNames) {
        Project project = resolveProjectByEpisodeId(episodeId);
        if (ProjectProductionMode.isComicCommentary(project)) {
            return comicCommentaryPanelPromptBuilder.buildGridPrompt(visualStyle, pageShots, charRefsWithNames);
        }
        return panelPromptBuilder.buildGridPrompt(visualStyle, pageShots, charRefsWithNames);
    }

    public static String appendUserHintToPrompt(String basePrompt, String customHint) {
        String normalized = customHint == null ? "" : customHint.trim();
        if (normalized.isEmpty()) {
            return basePrompt;
        }
        return basePrompt + "\n\n用户修改要求: " + normalized;
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
    public void generateGridsForEpisode(Long episodeId, List<Map<String, Object>> shots, String visualStyle, String imageProvider) {
        doGenerateGridsForEpisode(episodeId, shots, visualStyle, imageProvider, null);
    }

    @Async
    public void generateGridsForEpisode(Long episodeId, List<Map<String, Object>> shots, String visualStyle,
                                        String imageProvider, String customHint) {
        doGenerateGridsForEpisode(episodeId, shots, visualStyle, imageProvider, customHint);
    }

    private void doGenerateGridsForEpisode(Long episodeId, List<Map<String, Object>> shots, String visualStyle,
                                           String imageProvider, String customHint) {
        // 获取 projectId（在 try 外声明，catch 中也需要用）
        String gridProjectId = null;
        try {
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode == null) throw new BusinessException("Episode 不存在: " + episodeId);

            gridProjectId = episode.getProjectId();

            // 发布九宫格生成开始事件
            if (gridProjectId != null) {
                eventPublisher.publishEpisodeGridStatus(gridProjectId, episodeId, 0, "generating");
            }

            ImageGenerationService imageService = aiServiceConfig.getImageService(imageProvider != null ? imageProvider : "seedream");

            Map<String, Object> episodeInfo = episode.getEpisodeInfo();
            String initialGridStatus = (String) episodeInfo.getOrDefault("gridStatus", "generating");
            List<String> characterRefUrls = getCharacterReferenceUrls(episodeId);
            List<CharRef> charRefsWithNames = getCharacterReferencesWithNames(episodeId);
            int pageCount = calculatePageCount(shots.size(), SHOTS_PER_PAGE);
            List<String> gridImageUrls = new ArrayList<>();
            String lastPagePrompt = null;

            // 逐页生成九宫格
            for (int page = 0; page < pageCount; page++) {
                int fromIdx = page * SHOTS_PER_PAGE;
                int toIdx = Math.min(fromIdx + SHOTS_PER_PAGE, shots.size());
                List<Map<String, Object>> pageShots = shots.subList(fromIdx, toIdx);

                String prompt = buildGridPromptForProject(episodeId, visualStyle, pageShots, charRefsWithNames);
                prompt = appendUserHintToPrompt(prompt, customHint);
                lastPagePrompt = prompt;
                String imageUrl;
                if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
                    imageUrl = imageService.generateWithMultipleReferences(
                        prompt, characterRefUrls, 1920, 1080);
                } else {
                    imageUrl = imageService.generate(prompt, 1920, 1080, visualStyle);
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

            // 写入前检查：如果 gridStatus 已被重置为 "generating"（说明有更新的重新生成请求），放弃写入
            Episode freshEpisode = episodeRepository.selectById(episodeId);
            if (freshEpisode != null) {
                String freshStatus = (String) freshEpisode.getEpisodeInfo().getOrDefault("gridStatus", "");
                if (!initialGridStatus.equals(freshStatus)) {
                    log.warn("Episode {} 九宫格状态已被更新，放弃写入旧结果", episodeId);
                    return;
                }
            }

            // 更新 episodeInfo
            episodeInfo.put("gridImages", gridImageUrls);
            episodeInfo.put("splitShots", splitShots);
            episodeInfo.put("gridStatus", "generated");
            episodeInfo.put("gridPageCount", pageCount);
            // 保存最后一个 page 的 prompt（包含完整九宫格布局信息）
            episodeInfo.put("gridPrompt", lastPagePrompt);
            episode.setEpisodeInfo(episodeInfo);
            episodeRepository.updateById(episode);

            // 发布九宫格生成完成事件
            if (gridProjectId != null) {
                eventPublisher.publishEpisodeGridStatus(gridProjectId, episodeId, 0, "generated");
            }

            log.info("Episode {} 整集九宫格完成, {} 页, {} 分镜", episodeId, pageCount, shots.size());

        } catch (Exception e) {
            log.error("Episode {} 整集九宫格失败", episodeId, e);
            try {
                updateEpisodeGridStatus(episodeId, "failed");

                // 发布九宫格生成失败事件
                if (gridProjectId != null) {
                    eventPublisher.publishEpisodeGridStatus(gridProjectId, episodeId, 0, "failed");
                }

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
     * 为指定 Panel 的 splitShots 创建融合参考图（仅 URL 版本）
     */
    public BufferedImage createFusionImageForPanel(List<Map<String, Object>> panelShots, List<String> charRefUrls) {
        List<CharRef> charRefs = new ArrayList<>();
        if (charRefUrls != null) {
            for (String url : charRefUrls) {
                charRefs.add(new CharRef(url, null));
            }
        }
        return createFusionImage(panelShots, charRefs);
    }

    /**
     * 为指定 Panel 的 splitShots 创建融合参考图（带角色名字版本）
     */
    public BufferedImage createFusionImageForPanelWithNames(List<Map<String, Object>> panelShots, List<CharRef> charRefs) {
        return createFusionImage(panelShots, charRefs != null ? charRefs : new ArrayList<>());
    }

    /**
     * 角色引用（URL + 名字）
     */
    public static class CharRef {
        public final String url;
        public final String name;
        public final String species;
        public final String appearance;
        public final String role;
        public CharRef(String url, String name, String species, String appearance, String role) {
            this.url = url;
            this.name = name;
            this.species = species;
            this.appearance = appearance;
            this.role = role;
        }
        public CharRef(String url, String name) {
            this(url, name, null, null, null);
        }
    }

    /**
     * 获取 Episode 的角色参考图（公开版本，供外部调用）
     */
    public List<String> getCharacterReferenceUrlsForEpisode(Long episodeId) {
        return getCharacterReferenceUrls(episodeId);
    }

    /**
     * 获取 Episode 的角色参考图（含名字），公开版本
     */
    public List<CharRef> getCharacterReferencesWithNamesForEpisode(Long episodeId) {
        return getCharacterReferencesWithNames(episodeId);
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

    private BufferedImage createFusionImage(List<Map<String, Object>> shots, List<CharRef> charRefs) {
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
            g.fillRect(x + 2, y + 2, 48, 36);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 26));
            String label = NumberFormatter.toCircled(i + 1);
            g.drawString(label, x + 6, y + 28);

            // 角色名字只展示在底部角色参考图区域，避免覆盖到分镜场景图。
        }

        // 绘制底部角色参考图横条
        if (charRefs != null && !charRefs.isEmpty()) {
            int charCount = charRefs.size();
            int charW = (FIXED_WIDTH - PAD * (charCount + 1)) / charCount;
            int infoBarH = 36;  // 名字+设定信息的高度
            int charH = BOTTOM_BAR_HEIGHT - PAD * 2 - infoBarH;
            int charY = MAIN_AREA_HEIGHT + PAD;

            for (int i = 0; i < charRefs.size(); i++) {
                int charX = PAD + i * (charW + PAD);
                try {
                    BufferedImage charImg = downloadImage(charRefs.get(i).url);
                    // 等比缩放
                    double scale = Math.min((double) charW / charImg.getWidth(), (double) charH / charImg.getHeight());
                    int drawW = (int) (charImg.getWidth() * scale);
                    int drawH = (int) (charImg.getHeight() * scale);
                    int drawX = charX + (charW - drawW) / 2;
                    int drawY = charY + (charH - drawH) / 2;
                    g.drawImage(charImg, drawX, drawY, drawW, drawH, null);

                    // 角色编号
                    g.setColor(Color.BLACK);
                    g.fillRect(charX + 2, charY + 2, 36, 28);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("SansSerif", Font.BOLD, 18));
                    g.drawString("C" + (i + 1), charX + 5, charY + 22);

                    // 角色名字 + 设定信息
                    CharRef cr = charRefs.get(i);
                    String charName = cr.name;
                    List<String> infoParts = new ArrayList<>();
                    if (cr.role != null && !cr.role.isEmpty()) infoParts.add(cr.role);
                    if (cr.species != null && !cr.species.isEmpty()) infoParts.add(cr.species);
                    if (cr.appearance != null && !cr.appearance.isEmpty()) infoParts.add(cr.appearance);

                    if (charName != null && !charName.isEmpty()) {
                        // 角色名贴在角色图旁边（优先右侧，空间不足时自动夹紧在槽位内）
                        g.setFont(new Font("SansSerif", Font.BOLD, 13));
                        java.awt.FontMetrics nameFm = g.getFontMetrics();
                        int maxNameTextWidth = Math.max(40, charW - 16);
                        String displayName = truncateText(g, charName, maxNameTextWidth);
                        int namePaddingX = 8;
                        int namePaddingY = 3;
                        int badgeW = Math.min(nameFm.stringWidth(displayName) + namePaddingX * 2, charW - 8);
                        int badgeH = nameFm.getHeight() + namePaddingY * 2;
                        Point badgePos = computeNameBadgePosition(
                            charX, charY, charW, charH,
                            drawX, drawY, drawW, drawH,
                            badgeW, badgeH
                        );

                        g.setColor(new Color(0, 0, 0, 180));
                        g.fillRoundRect(badgePos.x, badgePos.y, badgeW, badgeH, 10, 10);
                        g.setColor(Color.WHITE);
                        g.drawString(displayName, badgePos.x + namePaddingX, badgePos.y + namePaddingY + nameFm.getAscent());
                    }
                    if (!infoParts.isEmpty()) {
                        // 设定信息放在角色图下方
                        int infoY = charY + charH + 3;
                        g.setColor(new Color(180, 180, 180));
                        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                        java.awt.FontMetrics infoFm = g.getFontMetrics();
                        String infoText = truncateText(g, String.join(" | ", infoParts), charW - 8);
                        int infoTextX = charX + (charW - infoFm.stringWidth(infoText)) / 2;
                        g.drawString(infoText, infoTextX, infoY + infoFm.getAscent());
                    }
                } catch (Exception e) {
                    log.warn("角色参考图加载失败: {}", charRefs.get(i).url);
                }
            }
        }

        g.dispose();
        return canvas;
    }

    /**
     * 截断文本以适配指定最大宽度
     */
    private String truncateText(Graphics2D g, String text, int maxWidth) {
        if (text == null) return "";
        java.awt.FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) return text;
        // 逐字符缩短直到适配
        for (int len = text.length() - 1; len > 0; len--) {
            String truncated = text.substring(0, len) + "…";
            if (fm.stringWidth(truncated) <= maxWidth) return truncated;
        }
        return "…";
    }

    static Point computeNameBadgePosition(
            int slotX,
            int slotY,
            int slotW,
            int slotH,
            int imageX,
            int imageY,
            int imageW,
            int imageH,
            int badgeW,
            int badgeH) {
        int minX = slotX + 4;
        int maxX = slotX + slotW - badgeW - 4;
        int preferredX = imageX + imageW + 6;
        int badgeX = Math.max(minX, Math.min(preferredX, maxX));

        int minY = slotY + 4;
        int maxY = slotY + slotH - badgeH - 4;
        int preferredY = imageY + 6;
        int badgeY = Math.max(minY, Math.min(preferredY, maxY));

        return new Point(badgeX, badgeY);
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

            // 记录已通过 charId 匹配的角色名，防止兜底循环重复添加
            java.util.Set<String> resolvedNames = new java.util.LinkedHashSet<>();

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
                    // 记录已匹配的角色名
                    Map<String, Object> chInfo = ch.getCharacterInfo();
                    if (chInfo != null) {
                        String chName = (String) chInfo.get(CharacterInfoKeys.NAME);
                        if (chName != null) resolvedNames.add(chName.trim());
                    }
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
                    if (resolvedNames.contains(charName)) continue; // 已通过 charId 匹配，跳过
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
     * 获取角色参考图（含名字），用于融合图绘制
     */
    private List<CharRef> getCharacterReferencesWithNames(Long episodeId) {
        List<CharRef> refs = new ArrayList<>();
        try {
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode == null) {
                log.warn("角色参考图(含名字): episodeId={} 不存在", episodeId);
                return refs;
            }

            Map<String, Object> episodeInfo = episode.getEpisodeInfo();
            if (episodeInfo == null) {
                log.warn("角色参考图(含名字): episodeId={} episodeInfo 为空", episodeId);
                return refs;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shots = (List<Map<String, Object>>) episodeInfo.get("shots");
            if (shots == null) {
                log.warn("角色参考图(含名字): episodeId={} shots 为空", episodeId);
                return refs;
            }

            // 收集 charId 和角色名
            java.util.Set<String> charIds = new java.util.LinkedHashSet<>();
            java.util.Set<String> charNames = new java.util.LinkedHashSet<>();

            for (Map<String, Object> shot : shots) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> charRefs = (List<Map<String, String>>) shot.get("characterRefs");
                if (charRefs != null) {
                    for (Map<String, String> ref : charRefs) {
                        String charId = ref.get("charId");
                        if (charId != null && !charId.isEmpty()) charIds.add(charId);
                        String name = ref.get("name");
                        if (name != null && !name.trim().isEmpty()) charNames.add(name.trim());
                    }
                } else {
                    @SuppressWarnings("unchecked")
                    List<String> characters = (List<String>) shot.get("characters");
                    if (characters != null) {
                        for (String c : characters) {
                            if (c != null && !c.trim().isEmpty()) charNames.add(c.trim());
                        }
                    }
                }
            }

            // 记录已通过 charId 匹配的角色名，防止兜底循环重复添加
            java.util.Set<String> resolvedNames = new java.util.LinkedHashSet<>();

            // 用 charId 匹配
            for (String charId : charIds) {
                Character ch = characterRepository.findByCharId(charId);
                if (ch == null) continue;
                CharRef ref = buildCharRef(ch);
                if (ref.url != null && !ref.url.isEmpty()) {
                    refs.add(ref);
                    if (ref.name != null) resolvedNames.add(ref.name);
                }
            }

            // 兜底：用角色名匹配
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
                    if (resolvedNames.contains(charName)) continue; // 已通过 charId 匹配，跳过
                    Character ch = nameToChar.get(charName);
                    if (ch == null) continue;
                    CharRef ref = buildCharRef(ch);
                    if (ref.url != null && !ref.url.isEmpty()) {
                        refs.add(ref);
                    }
                }
            }

            log.info("角色参考图(含名字): episodeId={} 最终数量={}", episodeId, refs.size());
        } catch (Exception e) {
            log.error("获取角色参考图(含名字)失败: episodeId={}", episodeId, e);
        }
        return refs;
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

    /**
     * 从 Character 实体构建完整的 CharRef（含物种、外貌、角色定位）
     */
    private CharRef buildCharRef(Character ch) {
        Map<String, Object> info = ch.getCharacterInfo();
        String name = info != null ? (String) info.get(CharacterInfoKeys.NAME) : null;
        String species = info != null ? (String) info.get(CharacterInfoKeys.SPECIES) : null;
        String appearance = info != null ? (String) info.get(CharacterInfoKeys.APPEARANCE) : null;
        String role = info != null ? (String) info.get(CharacterInfoKeys.ROLE) : null;
        String url = getCharacterImageUrl(ch);
        return new CharRef(url, name, species, appearance, role);
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
