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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GridImageService {

    /** 内存锁：正在生成单页九宫格的 "episodeId-pageIndex" 集合，防止同一页重复提交 */
    private static final java.util.Set<String> generatingPageLocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 内存锁：正在生成单 Panel 九宫格的 panelId 集合，防止重复提交 */
    private static final java.util.Set<Long> generatingPanelGridLocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** episode 级 DB 写锁：防止多页并发完成时互相覆盖 episodeInfo */
    private static final ConcurrentHashMap<Long, ReentrantLock> episodeUpdateLocks = new ConcurrentHashMap<>();

    private static final int GRID_SEPARATOR_PIXELS = 8;
    private static final Color FUSION_BG_COLOR = new Color(0x1a, 0x1a, 0x1c);
    private static final int GRID_IMAGE_WIDTH = 3840;
    private static final int GRID_IMAGE_HEIGHT = 2160;

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
        // 内存锁防重复
        if (!generatingPanelGridLocks.add(panelId)) {
            log.info("九宫格已在生成中，跳过: panelId={}", panelId);
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shots = (List<Map<String, Object>>) panelInfo.get("shots");
        String visualStyleStr = (String) panelInfo.getOrDefault("visualStyle", "ANIME");

        ImageGenerationService imageService = aiServiceConfig.getImageService(imageProvider != null ? imageProvider : "seedream");

        try {
            panelInfo.put("gridStatus", "generating");
            updatePanelInfo(panel, panelInfo);

            List<String> characterRefUrls = getCharacterReferenceUrls(panel.getEpisodeId());
            List<CharRef> charRefsWithNames = getCharacterReferencesWithNames(panel.getEpisodeId());

            // 动态分页：根据每页剩余分镜数选择最优网格尺寸
            List<int[]> pageGridSizes = new ArrayList<>();
            List<String> gridImageUrls = new ArrayList<>();
            List<Map<String, Object>> gridConfigs = new ArrayList<>();
            int remaining = shots.size();
            while (remaining > 0) {
                int[] gridSize = calculateGridSize(remaining);
                int capacity = shotsPerPage(gridSize[0], gridSize[1]);
                int actualShots = Math.min(capacity, remaining);
                pageGridSizes.add(gridSize);
                Map<String, Object> config = new HashMap<>();
                config.put("page", pageGridSizes.size() - 1);
                config.put("gridCols", gridSize[0]);
                config.put("gridRows", gridSize[1]);
                config.put("shotCount", actualShots);
                gridConfigs.add(config);
                remaining -= actualShots;
            }
            int pageCount = pageGridSizes.size();

            int shotOffset = 0;
            for (int page = 0; page < pageCount; page++) {
                int[] gridSize = pageGridSizes.get(page);
                int gridCols = gridSize[0];
                int gridRows = gridSize[1];
                int pageCapacity = shotsPerPage(gridCols, gridRows);
                int toIdx = Math.min(shotOffset + pageCapacity, shots.size());
                List<Map<String, Object>> pageShots = shots.subList(shotOffset, toIdx);

                String prompt = buildGridPromptForProject(panel.getEpisodeId(), visualStyleStr, pageShots, charRefsWithNames, gridCols, gridRows);
                prompt = appendUserHintToPrompt(prompt, customHint);
                String imageUrl;
                if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
                    imageUrl = imageService.generateWithMultipleReferences(
                        prompt, characterRefUrls, GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT);
                } else {
                    imageUrl = imageService.generate(prompt, GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT, visualStyleStr);
                }
                gridImageUrls.add(imageUrl);
                shotOffset = toIdx;
            }

            // 切割九宫格
            shotOffset = 0;
            for (int page = 0; page < gridImageUrls.size(); page++) {
                BufferedImage gridImage = downloadImage(gridImageUrls.get(page));
                int[] gridSize = pageGridSizes.get(page);
                List<BufferedImage> subImages = splitGridImage(gridImage, gridSize[0], gridSize[1]);
                for (int i = 0; i < subImages.size() && (shotOffset + i) < shots.size(); i++) {
                    String ossUrl = uploadToOss(subImages.get(i), panelId, shotOffset + i);
                    // 创建副本并更新 splitImageUrl，避免修改原始 shots
                    @SuppressWarnings("unchecked")
                    Map<String, Object> shotCopy = new HashMap<>(shots.get(shotOffset + i));
                    shotCopy.put("splitImageUrl", ossUrl);
                    shots.set(shotOffset + i, shotCopy);
                }
                shotOffset += shotsPerPage(gridSize[0], gridSize[1]);
            }

            // 融合参考图
            BufferedImage fusionImage = createFusionImage(shots, charRefsWithNames);
            panelInfo.put("fusionImageUrl", uploadToOss(fusionImage, panelId, "fusion"));
            panelInfo.put("gridImages", gridImageUrls);
            panelInfo.put("gridStatus", "generated");
            panelInfo.put("gridPageCount", pageCount);
            panelInfo.put("gridConfigs", gridConfigs);
            updatePanelInfo(panel, panelInfo);

            log.info("Panel {} 九宫格完成, {} 页, {} 分镜", panelId, pageCount, shots.size());

        } catch (Exception e) {
            log.error("Panel {} 九宫格失败", panelId, e);
            panelInfo.put("gridStatus", "failed");
            panelInfo.put("errorMessage", e.getMessage());
            updatePanelInfo(panel, panelInfo);
        } finally {
            generatingPanelGridLocks.remove(panelId);
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
                                             List<Map<String, Object>> pageShots, List<CharRef> charRefsWithNames,
                                             int gridCols, int gridRows) {
        Project project = resolveProjectByEpisodeId(episodeId);
        if (ProjectProductionMode.isComicCommentary(project)) {
            return comicCommentaryPanelPromptBuilder.buildGridPrompt(visualStyle, pageShots, charRefsWithNames, gridCols, gridRows);
        }
        return panelPromptBuilder.buildGridPrompt(visualStyle, pageShots, charRefsWithNames, gridCols, gridRows);
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
     * 核心方法：同步生成单页九宫格（生图 → 下载 → 切割 → 上传 OSS）
     * 不写 DB，调用方负责持久化。
     *
     * @return GridPageResult（含 imageUrl、prompt、splitShots），生成失败抛异常
     */
    private GridPageResult generateSinglePageSync(Long episodeId, int pageIndex, String prompt,
                                                   ImageGenerationService imageService,
                                                   List<String> characterRefUrls,
                                                   String visualStyle,
                                                   List<Map<String, Object>> shots,
                                                   List<int[]> pageGridSizes) {
        int shotOffset = 0;
        for (int i = 0; i < pageIndex; i++) {
            shotOffset += shotsPerPage(pageGridSizes.get(i)[0], pageGridSizes.get(i)[1]);
        }

        int[] gridSize = pageGridSizes.get(pageIndex);
        int gridCols = gridSize[0];
        int gridRows = gridSize[1];
        int pageCapacity = shotsPerPage(gridCols, gridRows);
        int toIdx = Math.min(shotOffset + pageCapacity, shots.size());
        List<Map<String, Object>> pageShots = shots.subList(shotOffset, toIdx);

        // 生成九宫格图片
        String imageUrl;
        if (characterRefUrls != null && !characterRefUrls.isEmpty()) {
            imageUrl = imageService.generateWithMultipleReferences(
                prompt, characterRefUrls, GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT);
        } else {
            imageUrl = imageService.generate(prompt, GRID_IMAGE_WIDTH, GRID_IMAGE_HEIGHT, visualStyle);
        }

        // 切割九宫格
        BufferedImage gridImage = downloadImage(imageUrl);
        List<BufferedImage> subImages = splitGridImage(gridImage, gridCols, gridRows);

        // 上传切割后的子图到 OSS
        List<Map<String, Object>> pageSplitShots = new ArrayList<>();
        for (int i = 0; i < subImages.size() && (shotOffset + i) < shots.size(); i++) {
            String ossUrl = uploadToOssEpisode(subImages.get(i), episodeId, shotOffset + i);
            Map<String, Object> shot = new HashMap<>(shots.get(shotOffset + i));
            shot.put("splitImageUrl", ossUrl);
            pageSplitShots.add(shot);
        }

        return new GridPageResult(pageIndex, imageUrl, prompt, pageSplitShots);
    }

    /**
     * 为指定 Episode 的某一页生成九宫格图（逐页生成）
     * 只处理指定 pageIndex 的页面，更新 gridImages[pageIndex] 和对应的 splitShots
     */
    @Async
    public void generateGridPage(Long episodeId, int pageIndex, String imageProvider, String customPrompt) {
        String gridProjectId = null;
        // per-page 锁：同一页防重复提交，不同页可并发
        String pageLockKey = episodeId + "-" + pageIndex;
        if (!generatingPageLocks.add(pageLockKey)) {
            log.info("九宫格已在生成中，跳过单页请求: episodeId={}, pageIndex={}", episodeId, pageIndex);
            return;
        }
        try {
            Episode episode = episodeRepository.selectById(episodeId);
            if (episode == null) throw new BusinessException("Episode 不存在: " + episodeId);
            gridProjectId = episode.getProjectId();

            Map<String, Object> episodeInfo = episode.getEpisodeInfo();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> shots = (List<Map<String, Object>>) episodeInfo.get("shots");
            if (shots == null || shots.isEmpty()) throw new BusinessException("没有分镜数据");

            String visualStyle = (String) episodeInfo.getOrDefault("visualStyle", "ANIME");
            ImageGenerationService imageService = aiServiceConfig.getImageService(imageProvider != null ? imageProvider : "seedream");
            List<String> characterRefUrls = getCharacterReferenceUrls(episodeId);
            List<CharRef> charRefsWithNames = getCharacterReferencesWithNames(episodeId);

            // 计算分页布局
            List<int[]> pageGridSizes = computePageGridSizes(shots.size());

            if (pageIndex < 0 || pageIndex >= pageGridSizes.size()) {
                throw new BusinessException("页码超出范围: " + pageIndex + ", 总页数: " + pageGridSizes.size());
            }

            // 构建 prompt
            String prompt;
            if (customPrompt != null && !customPrompt.isEmpty()) {
                prompt = customPrompt;
            } else {
                int[] gridSize = pageGridSizes.get(pageIndex);
                int shotOffset = computeShotOffset(pageIndex, pageGridSizes);
                int pageCapacity = shotsPerPage(gridSize[0], gridSize[1]);
                int toIdx = Math.min(shotOffset + pageCapacity, shots.size());
                List<Map<String, Object>> pageShots = shots.subList(shotOffset, toIdx);
                prompt = buildGridPromptForProject(episodeId, visualStyle, pageShots, charRefsWithNames, gridSize[0], gridSize[1]);
            }

            // 调用核心生成方法
            GridPageResult result = generateSinglePageSync(episodeId, pageIndex, prompt,
                imageService, characterRefUrls, visualStyle, shots, pageGridSizes);

            // 重新读取 episode（fresh snapshot）
            Episode freshEpisode = episodeRepository.selectById(episodeId);
            if (freshEpisode == null) throw new BusinessException("Episode 不存在: " + episodeId);
            Map<String, Object> freshInfo = freshEpisode.getEpisodeInfo();

            // 合并 splitShots
            int shotOffset = computeShotOffset(pageIndex, pageGridSizes);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> splitShots = (List<Map<String, Object>>) freshInfo.getOrDefault("splitShots", new ArrayList<>());
            for (int i = 0; i < result.splitShots.size(); i++) {
                int idx = shotOffset + i;
                while (splitShots.size() <= idx) {
                    splitShots.add(new HashMap<>());
                }
                splitShots.set(idx, result.splitShots.get(i));
            }

            // 合并 gridImages
            @SuppressWarnings("unchecked")
            List<String> gridImages = (List<String>) freshInfo.getOrDefault("gridImages", new ArrayList<>());
            while (gridImages.size() <= pageIndex) {
                gridImages.add(null);
            }
            gridImages.set(pageIndex, result.imageUrl);

            // 合并 gridPrompts
            @SuppressWarnings("unchecked")
            List<String> gridPrompts = (List<String>) freshInfo.getOrDefault("gridPrompts", new ArrayList<>());
            while (gridPrompts.size() <= pageIndex) {
                gridPrompts.add(null);
            }
            gridPrompts.set(pageIndex, result.prompt);

            // 合并 gridConfigs
            List<Map<String, Object>> gridConfigs = buildGridConfigs(pageGridSizes, shots.size());

            // 重新生成融合图
            String fusionUrl = null;
            List<Map<String, Object>> shotsWithUrl = splitShots.stream()
                .filter(s -> s.containsKey("splitImageUrl")).collect(Collectors.toList());
            if (!shotsWithUrl.isEmpty()) {
                BufferedImage fusionImage = createFusionImage(shotsWithUrl, charRefsWithNames);
                fusionUrl = uploadToOssEpisode(fusionImage, episodeId, "fusion");
            }

            // 写入 fresh episode（加 episode 级锁，防止并发页互相覆盖）
            ReentrantLock updateLock = episodeUpdateLocks.computeIfAbsent(episodeId, k -> new ReentrantLock());
            boolean allDone = false;
            List<String> finalPageStatuses = new ArrayList<>();
            updateLock.lock();
            try {
                // 重新读取最新数据，合并本页结果
                Episode latestEp = episodeRepository.selectById(episodeId);
                if (latestEp != null) {
                    Map<String, Object> latestInfo = latestEp.getEpisodeInfo();
                    // 合并 gridImages（保留其他页的图片）
                    @SuppressWarnings("unchecked")
                    List<String> existingImages = (List<String>) latestInfo.getOrDefault("gridImages", new ArrayList<>());
                    while (existingImages.size() <= pageIndex) existingImages.add(null);
                    existingImages.set(pageIndex, result.imageUrl);
                    freshInfo.put("gridImages", existingImages);

                    // 从最新数据合并 splitShots（避免并发页互相覆盖）
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> latestShots = (List<Map<String, Object>>) latestInfo.getOrDefault("splitShots", new ArrayList<>());
                    // 将本页 result 按 shotOffset 合并到 latestShots（而非 freshInfo 中的旧快照）
                    for (int i = 0; i < result.splitShots.size(); i++) {
                        int idx = shotOffset + i;
                        while (latestShots.size() <= idx) {
                            latestShots.add(new HashMap<>());
                        }
                        latestShots.set(idx, result.splitShots.get(i));
                    }
                    freshInfo.put("splitShots", latestShots);

                    // 用最新数据重新计算 per-page 状态
                    @SuppressWarnings("unchecked")
                    List<String> latestPageStatuses = (List<String>) latestInfo.getOrDefault("gridPageStatuses", new ArrayList<>());
                    while (latestPageStatuses.size() < pageGridSizes.size()) latestPageStatuses.add("pending");
                    latestPageStatuses.set(pageIndex, "generated");
                    freshInfo.put("gridPageStatuses", latestPageStatuses);

                    @SuppressWarnings("unchecked")
                    List<String> latestPageErrors = (List<String>) latestInfo.getOrDefault("gridPageErrors", new ArrayList<>());
                    while (latestPageErrors.size() < pageGridSizes.size()) latestPageErrors.add(null);
                    latestPageErrors.set(pageIndex, null);
                    freshInfo.put("gridPageErrors", latestPageErrors);

                    // 保留其他页的 gridPrompts
                    @SuppressWarnings("unchecked")
                    List<String> existingGridPrompts = (List<String>) latestInfo.getOrDefault("gridPrompts", new ArrayList<>());
                    while (existingGridPrompts.size() <= pageIndex) existingGridPrompts.add(null);
                    existingGridPrompts.set(pageIndex, result.prompt);
                    freshInfo.put("gridPrompts", existingGridPrompts);

                    // 保留 fusionImageUrl
                    if (latestInfo.containsKey("fusionImageUrl") && fusionUrl == null) {
                        freshInfo.put("fusionImageUrl", latestInfo.get("fusionImageUrl"));
                    }
                    if (fusionUrl != null) {
                        freshInfo.put("fusionImageUrl", fusionUrl);
                    }

                    // gridConfigs/pageCount
                    freshInfo.put("gridConfigs", gridConfigs);
                    freshInfo.put("gridPageCount", pageGridSizes.size());

                    // 推导全局 gridStatus
                    allDone = latestPageStatuses.stream().allMatch("generated"::equals);
                    boolean hasFailed = latestPageStatuses.stream().anyMatch("failed"::equals);
                    if (allDone) {
                        freshInfo.put("gridStatus", "generated");
                        freshInfo.remove("errorMessage");
                    } else if (hasFailed) {
                        freshInfo.put("gridStatus", "failed");
                    } else {
                        freshInfo.put("gridStatus", "generating");
                    }
                    finalPageStatuses = latestPageStatuses;

                    latestEp.setEpisodeInfo(freshInfo);
                    episodeRepository.updateById(latestEp);
                } else {
                    freshInfo.put("gridImages", gridImages);
                    freshInfo.put("gridConfigs", gridConfigs);
                    freshInfo.put("gridPageCount", pageGridSizes.size());
                    freshEpisode.setEpisodeInfo(freshInfo);
                    episodeRepository.updateById(freshEpisode);
                }
            } finally {
                updateLock.unlock();
            }

            String sseStatus = allDone ? "generated" : "generating";
            if (gridProjectId != null) {
                eventPublisher.publishEpisodeGridStatus(gridProjectId, episodeId, 0, sseStatus);
            }

            log.info("Episode {} 第 {} 页九宫格完成, gridPageStatuses={}", episodeId, pageIndex, finalPageStatuses);

        } catch (Exception e) {
            log.error("Episode {} 第 {} 页九宫格失败", episodeId, pageIndex, e);
            try {
                if (gridProjectId != null) {
                    eventPublisher.publishEpisodeGridStatus(gridProjectId, episodeId, 0, "failed");
                }
                // 加 episode 级锁写入失败状态
                ReentrantLock failLock = episodeUpdateLocks.computeIfAbsent(episodeId, k -> new ReentrantLock());
                failLock.lock();
                try {
                    Episode ep = episodeRepository.selectById(episodeId);
                    if (ep != null) {
                        Map<String, Object> info = ep.getEpisodeInfo();
                        String errorMsg = "第" + (pageIndex + 1) + "页生成失败: " + e.getMessage();

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> failShots = (List<Map<String, Object>>) info.get("shots");
                        int failTotalPages = computePageCount(failShots != null ? failShots.size() : 0);

                        List<String> gridPageStatuses = (List<String>) info.getOrDefault("gridPageStatuses", new ArrayList<>());
                        while (gridPageStatuses.size() < failTotalPages) gridPageStatuses.add("pending");
                        gridPageStatuses.set(pageIndex, "failed");
                        info.put("gridPageStatuses", gridPageStatuses);

                        List<String> gridPageErrors = (List<String>) info.getOrDefault("gridPageErrors", new ArrayList<>());
                        while (gridPageErrors.size() < failTotalPages) gridPageErrors.add(null);
                        gridPageErrors.set(pageIndex, errorMsg);
                        info.put("gridPageErrors", gridPageErrors);

                        boolean hasGenerating = gridPageStatuses.stream().anyMatch("generating"::equals);
                        if (!hasGenerating) {
                            info.put("gridStatus", "failed");
                        }
                        info.put("errorMessage", errorMsg);

                        ep.setEpisodeInfo(info);
                        episodeRepository.updateById(ep);
                    }
                } finally {
                    failLock.unlock();
                }
            } catch (Exception ex) {
                log.error("更新失败状态异常: episodeId={}", episodeId, ex);
            }
        } finally {
            generatingPageLocks.remove(pageLockKey);
        }
    }

    /**
     * 切割宫格图（纯函数）
     * 按比例切分：分隔线宽度根据实际图片尺寸按比例缩放，确保任何分辨率下切分比例正确
     */
    public static List<BufferedImage> splitGridImage(BufferedImage img, int cols, int rows) {
        List<BufferedImage> subImages = new ArrayList<>();
        int imgW = img.getWidth();
        int imgH = img.getHeight();

        // 分隔线按实际图片尺寸比例缩放（基准：3840宽度对应8px）
        double scale = (double) imgW / GRID_IMAGE_WIDTH;
        int sep = Math.max(1, (int) Math.round(GRID_SEPARATOR_PIXELS * scale));

        log.info("切分宫格: 实际图片 {}x{}, {}x{} 格, 分隔线 {}px", imgW, imgH, cols, rows, sep);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int cellW = (imgW - (cols - 1) * sep) / cols;
                int cellH = (imgH - (rows - 1) * sep) / rows;
                int x = c * (cellW + sep);
                int y = r * (cellH + sep);
                int w = cellW;
                int h = cellH;

                // 最后一列/行取剩余像素，避免累积偏差
                if (c == cols - 1) {
                    w = imgW - x;
                }
                if (r == rows - 1) {
                    h = imgH - y;
                }

                subImages.add(img.getSubimage(x, y, w, h));
            }
        }
        return subImages;
    }

    /**
     * 根据分镜数量计算该页最优网格布局
     * @return int[]{cols, rows}
     */
    public static int[] calculateGridSize(int shotCount) {
        if (shotCount <= 4) return new int[]{2, 2};
        if (shotCount <= 9) return new int[]{3, 3};
        if (shotCount <= 16) return new int[]{4, 4};
        return new int[]{5, 5};
    }

    /** 根据网格尺寸计算该页最大容量 */
    public static int shotsPerPage(int cols, int rows) {
        return cols * rows;
    }

    /**
     * 计算总分页数（供 Controller 等外部调用）
     */
    public static int computePageCount(int totalShots) {
        return computePageGridSizes(totalShots).size();
    }

    /**
     * 计算分页布局（提取公共逻辑）
     */
    static List<int[]> computePageGridSizes(int totalShots) {
        List<int[]> pageGridSizes = new ArrayList<>();
        int remaining = totalShots;
        while (remaining > 0) {
            int[] gridSize = calculateGridSize(remaining);
            pageGridSizes.add(gridSize);
            remaining -= shotsPerPage(gridSize[0], gridSize[1]);
        }
        return pageGridSizes;
    }

    /**
     * 构建 gridConfigs（提取公共逻辑）
     */
    static List<Map<String, Object>> buildGridConfigs(List<int[]> pageGridSizes, int totalShots) {
        List<Map<String, Object>> gridConfigs = new ArrayList<>();
        int remaining = totalShots;
        for (int i = 0; i < pageGridSizes.size(); i++) {
            int[] gridSize = pageGridSizes.get(i);
            int capacity = shotsPerPage(gridSize[0], gridSize[1]);
            int actualShots = Math.min(capacity, remaining);
            Map<String, Object> config = new HashMap<>();
            config.put("page", i);
            config.put("gridCols", gridSize[0]);
            config.put("gridRows", gridSize[1]);
            config.put("shotCount", actualShots);
            gridConfigs.add(config);
            remaining -= actualShots;
        }
        return gridConfigs;
    }

    /**
     * 构建所有页的 prompt（提取公共逻辑）
     */
    private List<String> buildAllPagePrompts(Long episodeId, String visualStyle,
                                             List<Map<String, Object>> shots, List<CharRef> charRefsWithNames,
                                             List<int[]> pageGridSizes,
                                             List<String> gridPrompts, String promptOverride, String customHint) {
        List<String> pagePrompts = new ArrayList<>();
        int shotOffset = 0;
        for (int page = 0; page < pageGridSizes.size(); page++) {
            int[] gridSize = pageGridSizes.get(page);
            int gridCols = gridSize[0];
            int gridRows = gridSize[1];
            int pageCapacity = shotsPerPage(gridCols, gridRows);
            int toIdx = Math.min(shotOffset + pageCapacity, shots.size());
            List<Map<String, Object>> pageShots = shots.subList(shotOffset, toIdx);

            String prompt;
            if (gridPrompts != null && page < gridPrompts.size() && gridPrompts.get(page) != null && !gridPrompts.get(page).isEmpty()) {
                prompt = gridPrompts.get(page);
            } else if (promptOverride != null && page == 0) {
                prompt = promptOverride;
            } else {
                prompt = buildGridPromptForProject(episodeId, visualStyle, pageShots, charRefsWithNames, gridCols, gridRows);
                prompt = appendUserHintToPrompt(prompt, customHint);
            }
            pagePrompts.add(prompt);
            shotOffset = toIdx;
        }
        return pagePrompts;
    }

    /**
     * 计算指定页的 shotOffset
     */
    static int computeShotOffset(int pageIndex, List<int[]> pageGridSizes) {
        int offset = 0;
        for (int i = 0; i < pageIndex; i++) {
            offset += shotsPerPage(pageGridSizes.get(i)[0], pageGridSizes.get(i)[1]);
        }
        return offset;
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
     * 单页生成结果（纯数据，不写 DB）
     */
    public static class GridPageResult {
        public final int pageIndex;
        public final String imageUrl;
        public final String prompt;
        public final List<Map<String, Object>> splitShots; // 本页切割后的 shot 列表（带 splitImageUrl）

        public GridPageResult(int pageIndex, String imageUrl, String prompt, List<Map<String, Object>> splitShots) {
            this.pageIndex = pageIndex;
            this.imageUrl = imageUrl;
            this.prompt = prompt;
            this.splitShots = splitShots;
        }
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
        int[] fusionGrid = calculateGridSize(shots.size());
        int fCols = fusionGrid[0];
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
            // 绘制编号 + 中文标注 + 秒数
            g.setColor(new Color(0, 0, 0, 200));
            g.fillRect(x + 2, y + 2, 72, 48);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 30));
            g.drawString("分镜" + (i + 1) + "号", x + 5, y + 30);
            Integer duration = (Integer) shots.get(i).get("duration");
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            g.drawString(duration != null ? duration + "秒" : "", x + 5, y + 46);

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

                    // 角色编号（放大）
                    g.setColor(new Color(0, 0, 0, 200));
                    g.fillRect(charX + 2, charY + 2, 56, 40);
                    g.setColor(Color.WHITE);
                    g.setFont(new Font("SansSerif", Font.BOLD, 26));
                    g.drawString("C" + (i + 1), charX + 8, charY + 30);

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

            log.debug("角色参考图(含名字): episodeId={} 最终数量={}", episodeId, refs.size());
        } catch (Exception e) {
            log.error("获取角色参考图(含名字)失败: episodeId={}", episodeId, e);
        }
        return refs;
    }

    /**
     * 获取角色的参考图 URL（三视图或表情图）
     * 主角/反派同时有三视图和表情图时，自动上下拼接并缓存
     */
    private String getCharacterImageUrl(Character ch) {
        Map<String, Object> info = ch.getCharacterInfo();
        if (info == null) return null;

        String threeViewUrl = (String) info.get(CharacterInfoKeys.THREE_VIEW_GRID_URL);
        String expressionUrl = (String) info.get(CharacterInfoKeys.EXPRESSION_GRID_URL);
        String role = (String) info.get(CharacterInfoKeys.ROLE);

        // 主角/反派同时有三视图和表情图时，上下拼接
        if (("主角".equals(role) || "反派".equals(role))
                && threeViewUrl != null && !threeViewUrl.isEmpty()
                && expressionUrl != null && !expressionUrl.isEmpty()) {
            String compositeUrl = (String) info.get(CharacterInfoKeys.COMPOSITE_REFERENCE_URL);
            if (compositeUrl != null && !compositeUrl.isEmpty()) {
                return compositeUrl;
            }
            try {
                compositeUrl = ossService.combineImagesVertical(threeViewUrl, expressionUrl);
                info.put(CharacterInfoKeys.COMPOSITE_REFERENCE_URL, compositeUrl);
                characterRepository.updateById(ch);
                log.info("角色参考图拼接完成: charId={}, url={}", ch.getId(), compositeUrl);
                return compositeUrl;
            } catch (Exception e) {
                log.warn("角色参考图拼接失败，降级使用三视图: charId={}, error={}", ch.getId(), e.getMessage());
            }
        }

        // 只有一张图或非主角/反派：保持原逻辑
        if (threeViewUrl != null && !threeViewUrl.isEmpty()) {
            return threeViewUrl;
        }
        return expressionUrl;
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
