import { useEffect, useRef, useState, useCallback } from 'react';
import styles from './GridFusionEditor.module.less';
import { useFusionStore, autoAssignCharacters } from '../../../../stores/fusionStore';
import { getGridInfo, uploadFusionImage, submitFusionPage } from '../../../../services/episodeService';
import { loadImage, loadImages } from '../utils/imageLoader';
import { splitGridToPanels, canvasToFile } from '../utils/canvasSplitter';
import GridCanvas from './GridCanvas';
import CharacterPalette from './CharacterPalette';
import PanelToolbar from './PanelToolbar';

interface GridFusionEditorProps {
  episodeId: string;
  onFusionSubmitted: () => void;
}

const GridFusionEditor = ({ episodeId, onFusionSubmitted }: GridFusionEditorProps) => {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [fusedPages, setFusedPages] = useState<Set<number>>(new Set());
  const characterImagesRef = useRef<Map<string, HTMLImageElement>>(new Map());

  const {
    gridInfo,
    sceneGridImage,
    currentPageIndex,
    selectedPanelIndex,
    selectedCharacterIndex,
    setGridInfo,
    setGridImage,
    setCurrentPageIndex,
    assignCharacterToPanel,
    isSubmitting,
    isUploading,
    setSubmitting,
    setUploading,
  } = useFusionStore();

  // Load grid info and images on mount
  useEffect(() => {
    let cancelled = false;

    async function init() {
      try {
        setLoading(true);
        setError(null);

        const res = await getGridInfo(episodeId);
        if (res.code !== 200 || !res.data) {
          throw new Error('获取网格信息失败');
        }

        if (cancelled) return;

        const info = res.data;
        setGridInfo(info);

        // Load all grid page images (P1-1 multi-page)
        if (info.gridPages && info.gridPages.length > 0) {
          for (let i = 0; i < info.gridPages.length; i++) {
            const img = await loadImage(info.gridPages[i].sceneGridUrl);
            if (cancelled) return;
            setGridImage(i, img);
          }
        } else if (info.sceneGridUrl) {
          const gridImg = await loadImage(info.sceneGridUrl);
          if (cancelled) return;
          setGridImage(0, gridImg);
        }

        // Pre-load all character reference images
        const allUrls: string[] = [];
        info.characterReferences.forEach((char) => {
          if (char.threeViewGridUrl) allUrls.push(char.threeViewGridUrl);
          if (char.expressionGridUrl) allUrls.push(char.expressionGridUrl);
        });
        characterImagesRef.current = await loadImages(allUrls);

        // Auto-assign characters to panels
        const overlays = autoAssignCharacters(info);
        useFusionStore.setState({ panelOverlays: overlays });

        // 恢复已融合的页
        if (info.gridPages) {
          const fused = new Set<number>();
          info.gridPages.forEach((page, i) => {
            if (page.fused) fused.add(i);
          });
          setFusedPages(fused);
        }

        setLoading(false);
      } catch (e: any) {
        if (!cancelled) {
          setError(e.message || '加载网格信息失败');
          setLoading(false);
        }
      }
    }

    init();
    return () => { cancelled = true; };
  }, [episodeId, setGridInfo, setGridImage]);

  const totalPages = gridInfo?.totalPages ?? (gridInfo?.sceneGridUrl ? 1 : 0);
  const currentPageInfo = gridInfo?.gridPages?.[currentPageIndex];
  const isLastPage = currentPageIndex >= totalPages - 1;

  const handlePrevPage = useCallback(() => {
    if (currentPageIndex > 0) setCurrentPageIndex(currentPageIndex - 1);
  }, [currentPageIndex, setCurrentPageIndex]);

  const handleNextPage = useCallback(() => {
    if (currentPageIndex < totalPages - 1) setCurrentPageIndex(currentPageIndex + 1);
  }, [currentPageIndex, totalPages, setCurrentPageIndex]);

  const handleAssignToPanel = useCallback(() => {
    if (selectedPanelIndex !== null && selectedCharacterIndex !== null) {
      assignCharacterToPanel(selectedPanelIndex, selectedCharacterIndex);
    }
  }, [selectedPanelIndex, selectedCharacterIndex, assignCharacterToPanel]);

  const handleSubmitCurrentPage = useCallback(async () => {
    if (!sceneGridImage || !gridInfo) return;

    try {
      setUploading(true);

      const { gridColumns, gridRows, panelWidth, panelHeight, separatorPixels } = gridInfo;
      const basePanels = splitGridToPanels(
        sceneGridImage, gridColumns, gridRows, panelWidth, panelHeight, separatorPixels
      );

      const fusedPanels = new Map<number, HTMLCanvasElement>();
      const state = useFusionStore.getState();

      // 逐格拼合：每个格子独立生成融合图
      basePanels.forEach((panelCanvas, panelIndex) => {
        const overlay = state.panelOverlays.get(panelIndex);
        let resultCanvas = panelCanvas;

        if (overlay) {
          const charImg = characterImagesRef.current.get(overlay.imageUrl);
          if (charImg) {
            resultCanvas = document.createElement('canvas');
            resultCanvas.width = panelWidth;
            resultCanvas.height = panelHeight;
            const ctx = resultCanvas.getContext('2d')!;
            ctx.drawImage(panelCanvas, 0, 0);

            ctx.globalAlpha = overlay.opacity;
            const drawWidth = panelWidth * overlay.scale;
            const drawHeight = panelHeight * overlay.scale;
            const drawX = overlay.x + (panelWidth - drawWidth) / 2;
            const drawY = overlay.y + (panelHeight - drawHeight) / 2;
            ctx.drawImage(charImg, drawX, drawY, drawWidth, drawHeight);
            ctx.globalAlpha = 1.0;
          }
        }

        fusedPanels.set(panelIndex, resultCanvas);
      });

      // 逐格上传：每个格子独立上传到OSS，收集所有URL
      const panelFusedUrls: string[] = [];
      const totalPanels = gridColumns * gridRows;

      for (let i = 0; i < totalPanels; i++) {
        const panelCanvas = fusedPanels.get(i);
        if (panelCanvas) {
          const file = await canvasToFile(panelCanvas);
          const uploadRes = await uploadFusionImage(episodeId, file);
          if (uploadRes.code !== 200 || !uploadRes.data) {
            throw new Error(`第${i + 1}格上传失败`);
          }
          panelFusedUrls.push(uploadRes.data);
        } else {
          panelFusedUrls.push(''); // 未设置角色的格子传空字符串
        }
      }

      // Submit this page's fusion (9个URL)
      setSubmitting(true);
      setUploading(false);
      await submitFusionPage(episodeId, currentPageIndex, panelFusedUrls);

      setSubmitting(false);
      setFusedPages((prev) => new Set(prev).add(currentPageIndex));

      // Auto-advance to next page
      if (!isLastPage) {
        setCurrentPageIndex(currentPageIndex + 1);
      } else {
        // All pages fused, pipeline will auto-resume on backend
        onFusionSubmitted();
      }
    } catch (e: any) {
      setUploading(false);
      setSubmitting(false);
      setError(e.message || '提交失败');
    }
  }, [sceneGridImage, gridInfo, episodeId, currentPageIndex, isLastPage, onFusionSubmitted, setUploading, setSubmitting, setCurrentPageIndex]);

  if (loading) {
    return <div className={styles.loadingState}>正在加载网格信息...</div>;
  }

  if (error) {
    return <div className={styles.errorState}>加载失败: {error}</div>;
  }

  if (!gridInfo || !sceneGridImage) {
    return <div className={styles.loadingState}>无网格数据</div>;
  }

  return (
    <div className={styles.fusionLayout}>
      <div className={styles.canvasSection}>
        {/* Page navigation */}
        {totalPages > 1 && (
          <div className={styles.pageNav}>
            <button
              className={styles.pageNavButton}
              onClick={handlePrevPage}
              disabled={currentPageIndex === 0}
            >
              &lt; 上一页
            </button>
            <span className={styles.pageIndicator}>
              第 {currentPageIndex + 1}/{totalPages} 页
              {currentPageInfo?.location ? ` - ${currentPageInfo.location}` : ''}
            </span>
            <button
              className={styles.pageNavButton}
              onClick={handleNextPage}
              disabled={isLastPage}
            >
              下一页 &gt;
            </button>
          </div>
        )}

        <div className={styles.canvasContainer}>
          <GridCanvas
            sceneGridImage={sceneGridImage}
            characterImages={characterImagesRef.current}
            gridColumns={gridInfo.gridColumns}
            gridRows={gridInfo.gridRows}
            panelWidth={gridInfo.panelWidth}
            panelHeight={gridInfo.panelHeight}
            separatorPixels={gridInfo.separatorPixels}
          />
        </div>

        {/* Fused progress for multi-page */}
        {totalPages > 1 && (
          <div className={styles.fusedProgress}>
            已融合: {fusedPages.size}/{totalPages} 页
            <div className={styles.fusedProgressBar}>
              <div
                className={styles.fusedProgressFill}
                style={{ width: `${(fusedPages.size / totalPages) * 100}%` }}
              />
            </div>
          </div>
        )}

        <PanelToolbar
          onAssignToPanel={handleAssignToPanel}
          canAssign={selectedPanelIndex !== null && selectedCharacterIndex !== null}
        />

        <div className={styles.submitSection}>
          <button
            className={styles.submitButton}
            onClick={handleSubmitCurrentPage}
            disabled={isUploading || isSubmitting || fusedPages.has(currentPageIndex)}
          >
            {fusedPages.has(currentPageIndex)
              ? '已提交'
              : isUploading
              ? '正在上传...'
              : isSubmitting
              ? '正在提交...'
              : totalPages > 1
              ? `提交第 ${currentPageIndex + 1} 页融合结果`
              : '提交融合结果'}
          </button>
          {(isUploading || isSubmitting) && (
            <span className={styles.submitStatus}>
              {isUploading ? '正在上传融合图到云端...' : '正在恢复生产管线...'}
            </span>
          )}
        </div>

        <p className={styles.helpText}>
          点击面板选择 → 从右侧选择角色分配 → 拖拽调整位置 → 滑块调整透明度/缩放 → 提交
        </p>
      </div>

      <CharacterPalette characters={gridInfo.characterReferences} />
    </div>
  );
};

export default GridFusionEditor;
