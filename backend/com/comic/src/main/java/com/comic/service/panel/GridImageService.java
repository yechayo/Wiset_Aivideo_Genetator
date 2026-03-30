package com.comic.service.panel;

import com.comic.ai.PanelPromptBuilder;
import com.comic.ai.image.SeedreamImageService;
import com.comic.common.BusinessException;
import com.comic.entity.Panel;
import com.comic.repository.PanelRepository;
import com.comic.service.oss.OssService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class GridImageService {

    private static final int GRID_COLS = 3;
    private static final int GRID_ROWS = 3;
    private static final int SHOTS_PER_PAGE = GRID_COLS * GRID_ROWS;
    private static final Color FUSION_BG_COLOR = new Color(0x1a, 0x1a, 0x1c);

    @Resource private SeedreamImageService seedreamImageService;
    @Resource private PanelPromptBuilder panelPromptBuilder;
    @Resource private PanelRepository panelRepository;
    @Resource private OssService ossService;

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
                    shots.get(fromIdx + i).put("splitImageUrl", ossUrl);
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

    /** 切割九宫格（纯函数） */
    public static List<BufferedImage> splitGridImage(BufferedImage img, int cols, int rows) {
        List<BufferedImage> subImages = new ArrayList<>();
        int pw = (int) Math.floor((double) img.getWidth() / cols);
        int ph = (int) Math.floor((double) img.getHeight() / rows);
        for (int i = 0; i < rows * cols; i++) {
            subImages.add(img.getSubimage((i % cols) * pw, (i / cols) * ph, pw, ph));
        }
        return subImages;
    }

    /** 分页数（纯函数） */
    public static int calculatePageCount(int totalShots, int shotsPerPage) {
        return totalShots <= 0 ? 0 : (int) Math.ceil((double) totalShots / shotsPerPage);
    }

    private BufferedImage createFusionImage(List<Map<String, Object>> shots, List<String> charRefUrls) {
        int fCols = 3, cellW = 640, cellH = 360, pad = 8, headerH = 60;
        int fRows = (int) Math.ceil((double) shots.size() / fCols);
        int cw = fCols * (cellW + pad) + pad;
        int ch = headerH + fRows * (cellH + pad) + pad;

        BufferedImage canvas = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(FUSION_BG_COLOR);
        g.fillRect(0, 0, cw, ch);

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("分镜融合图 - 共" + shots.size() + "个镜头", pad, headerH - 15);

        for (int i = 0; i < shots.size(); i++) {
            int x = pad + (i % fCols) * (cellW + pad);
            int y = headerH + pad + (i / fCols) * (cellH + pad);
            String splitUrl = (String) shots.get(i).get("splitImageUrl");
            if (splitUrl != null) {
                try {
                    BufferedImage sub = downloadImage(splitUrl);
                    double scale = Math.min((double) cellW / sub.getWidth(), (double) cellH / sub.getHeight());
                    g.drawImage(sub, x + (cellW - (int)(sub.getWidth()*scale))/2,
                        y + (cellH - (int)(sub.getHeight()*scale))/2,
                        (int)(sub.getWidth()*scale), (int)(sub.getHeight()*scale), null);
                } catch (Exception e) { log.warn("融合图加载失败: shot {}", i); }
            }
            g.setColor(Color.BLACK);
            g.fillRect(x+4, y+4, 70, 22);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("Shot " + (i+1), x+10, y+19);
        }
        g.dispose();
        return canvas;
    }

    private List<String> getCharacterReferenceUrls(Long episodeId) {
        // TODO: 从 CharacterService 获取角色参考图 URL
        return new ArrayList<>();
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
}
