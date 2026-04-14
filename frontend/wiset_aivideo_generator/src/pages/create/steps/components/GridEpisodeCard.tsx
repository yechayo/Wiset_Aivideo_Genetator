import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { EpisodeState } from '../types';
import { StoryboardGrid } from './StoryboardGrid';
import styles from '../Step4Production.module.less';

const SpinIcon = () => <span className={styles.btnSpinner} />;

function getGridStatusBadge(status: string) {
  switch (status) {
    case 'approved': return { text: '已通过', className: styles.statusApproved };
    case 'generated': return { text: '待审核', className: styles.statusGenerated };
    case 'rejected': return { text: '已退回', className: styles.statusRejected };
    case 'generating': return { text: '排队中', className: styles.statusGenerating, pulse: true };
    default: return { text: '待生成', className: styles.statusPending };
  }
}

interface GridEpisodeCardProps {
  episode: EpisodeState;
  generatingGrid: number | null;
  approvingEpisodeId: number | null;
  rejectingEpisodeId: number | null;
  /** 支持多页 prompts: 如果有值则传多页,否则传单页 fullPrompt */
  onGenerateGrid: (episodeId: number, fullPrompt?: string, gridPrompts?: string[]) => void;
  onApproveGrid: (episodeId: number) => void;
  onRejectGrid: (episodeId: number, reason: string) => void;
  onRejectToScript: (episodeId: number) => void;
  onOpenLightbox: (url: string) => void;
  buildGridPromptText?: (visualStyle: string, shots: any[], isComicCommentary?: boolean, gridCols?: number, gridRows?: number) => string;
}

/** 根据分镜数量计算网格布局（与后端一致） */
function getGridSize(shotCount: number): { cols: number; rows: number } {
  if (shotCount <= 4) return { cols: 2, rows: 2 };
  return { cols: 3, rows: 3 };
}

function buildAdaptivePages(totalShots: number): Array<{ fromIdx: number; toIdx: number; cols: number; rows: number }> {
  const pages: Array<{ fromIdx: number; toIdx: number; cols: number; rows: number }> = [];
  let offset = 0;
  let remaining = totalShots;
  while (remaining > 0) {
    const { cols, rows } = getGridSize(remaining);
    const capacity = cols * rows;
    const take = Math.min(capacity, remaining);
    pages.push({ fromIdx: offset, toIdx: offset + take, cols, rows });
    offset += take;
    remaining -= take;
  }
  return pages;
}

const GridEpisodeCard = React.memo(function GridEpisodeCard({
  episode,
  generatingGrid,
  approvingEpisodeId,
  rejectingEpisodeId,
  onGenerateGrid,
  onApproveGrid,
  onRejectGrid,
  onRejectToScript,
  onOpenLightbox,
  buildGridPromptText,
}: GridEpisodeCardProps) {
  const badge = getGridStatusBadge(episode.gridStatus || 'pending');
  // 每页独立的编辑状态
  const [editedPrompts, setEditedPrompts] = useState<string[]>([]);
  // 当前查看的页码
  const [currentPage, setCurrentPage] = useState(1);
  // 追踪用户是否手动编辑过 prompt，避免 SSE/polling 更新时覆盖用户编辑
  const hasLocalEditRef = useRef(false);

  // 是否可以编辑 prompt（approved 和 generating 状态不可编辑）
  const canEditPrompt = episode.gridStatus !== 'approved' && episode.gridStatus !== 'generating';
  // 当前 episode 是否正在生成中
  const isGeneratingThisEpisode = generatingGrid === episode.episodeId;

  // 是否有脚本数据
  const hasScript = episode.segments.length > 0 || (episode.episodeInfo?.shots?.length ?? 0) > 0;

  // 计算总页数
  const totalPages = episode.gridImages?.length || 1;

  // 获取所有 shots 数据
  const getAllShots = useCallback(() => {
    const shotsData = episode.segments.length > 0
      ? episode.segments
      : (episode.episodeInfo?.shots || []);
    return shotsData.map((s: any) => s.shots?.[0] || s).filter(Boolean);
  }, [episode.segments, episode.episodeInfo?.shots]);

  // 获取视觉风格
  const getVisualStyle = useCallback(() => {
    return episode.segments[0]?.panelData?.visualStyle
      || (episode.episodeInfo?.visualStyle as string)
      || 'ANIME';
  }, [episode.segments, episode.episodeInfo?.visualStyle]);

  // 生成某一页的 prompt
  const buildPagePrompt = useCallback((pageIndex: number): string => {
    if (!buildGridPromptText) return '';
    const allShots = getAllShots();
    const visualStyle = getVisualStyle();
    const config = (episode as any).gridConfigs?.[pageIndex];
    const cols = config?.gridCols ?? 3;
    const rows = config?.gridRows ?? 3;
    const capacity = cols * rows;
    let fromIdx = 0;
    const configs = (episode as any).gridConfigs;
    if (configs && configs.length > pageIndex) {
      for (let i = 0; i < pageIndex; i++) {
        fromIdx += configs[i].shotCount ?? (configs[i].gridCols * configs[i].gridRows);
      }
    } else {
      const adaptivePages = buildAdaptivePages(allShots.length);
      fromIdx = adaptivePages[pageIndex]?.fromIdx ?? pageIndex * 9;
    }
    const toIdx = Math.min(fromIdx + capacity, allShots.length);
    const pageShots = allShots.slice(fromIdx, toIdx);
    return buildGridPromptText(visualStyle, pageShots, false, cols, rows);
  }, [buildGridPromptText, getAllShots, getVisualStyle, episode]);

  // 初始化/重置 editedPrompts（当 episodeId 或 gridStatus 变化时）
  useEffect(() => {
    hasLocalEditRef.current = false;
    const allShots = getAllShots();
    const visualStyle = getVisualStyle();
    const pageCount = buildAdaptivePages(allShots.length).length || 1;

    if (canEditPrompt && buildGridPromptText && allShots.length > 0) {
      // 如果有保存的多页 prompts，使用它们；否则自动生成
      if (episode.gridPrompts && episode.gridPrompts.length > 0) {
        setEditedPrompts(episode.gridPrompts);
      } else if (episode.gridPrompt) {
        // 兼容旧的单 prompt：只设置第一页
        const prompts: string[] = [];
        for (let i = 0; i < pageCount; i++) {
          prompts.push(i === 0 ? episode.gridPrompt : buildPagePrompt(i));
        }
        setEditedPrompts(prompts);
      } else {
        // 没有任何保存的 prompt，全部自动生成
        const prompts: string[] = [];
        for (let i = 0; i < pageCount; i++) {
          prompts.push(buildPagePrompt(i));
        }
        setEditedPrompts(prompts);
      }
    } else {
      setEditedPrompts([]);
    }
  }, [episode.episodeId, episode.gridStatus, episode.gridPrompts, episode.gridPrompt]);

  // gridPrompt / gridPrompts 从后端加载后同步（仅在用户未手动编辑时）
  useEffect(() => {
    if (hasLocalEditRef.current) return;
    if (canEditPrompt) {
      if (episode.gridPrompts && episode.gridPrompts.length > 0) {
        setEditedPrompts(episode.gridPrompts);
      } else if (episode.gridPrompt) {
        const allShots = getAllShots();
        const pageCount = buildAdaptivePages(allShots.length).length || 1;
        const prompts: string[] = [];
        for (let i = 0; i < pageCount; i++) {
          prompts.push(i === 0 ? episode.gridPrompt : buildPagePrompt(i));
        }
        setEditedPrompts(prompts);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [episode.gridPrompt, episode.gridPrompts, canEditPrompt, getAllShots, buildPagePrompt]);

  // 更新某一页的 prompt
  const updatePrompt = useCallback((pageIndex: number, value: string) => {
    setEditedPrompts(prev => {
      const next = [...prev];
      next[pageIndex] = value;
      return next;
    });
    hasLocalEditRef.current = true;
  }, []);

  const handleRejectClick = useCallback(() => {
    const reason = prompt('请给出你的优化建议:');
    if (reason) onRejectGrid(episode.episodeId, reason);
  }, [episode.episodeId, onRejectGrid]);

  const handleGenerateClick = useCallback(() => {
    // 只有用户手动编辑过 prompt 时才传前端 prompts，否则让后端重新动态生成
    if (hasLocalEditRef.current && editedPrompts.length > 0) {
      onGenerateGrid(episode.episodeId, editedPrompts[0], editedPrompts);
    } else {
      onGenerateGrid(episode.episodeId);
    }
  }, [episode.episodeId, onGenerateGrid, editedPrompts, canEditPrompt]);

  const handleRejectToScriptClick = useCallback(() => {
    if (confirm('确定要退回脚本阶段吗？九宫格数据将被清除。')) {
      onRejectToScript(episode.episodeId);
    }
  }, [episode.episodeId, onRejectToScript]);

  // 获取当前页的 prompt（用于显示）
  const currentPrompt = editedPrompts[currentPage - 1] || '';

  return (
    <div className={styles.episodeGridCard}>
      <div className={styles.episodeGridHeader}>
        <div>
          <h3 className={styles.episodeGridTitle}>
            第{episode.episodeIndex}集 {episode.title}
          </h3>
          <span className={`${styles.statusBadge} ${badge.className}`}>
            <span className={`${styles.statusDot} ${badge.pulse ? styles.statusDotPulse : ''}`} />
            {badge.text}
          </span>
          {episode.gridRejectionFeedback && (
            <div className={styles.gridRejectionFeedback}>
              退回原因：{episode.gridRejectionFeedback}
            </div>
          )}
        </div>
        <div className={styles.episodeGridActions}>
          {episode.gridStatus !== 'approved' && episode.gridStatus !== 'generated' && (
            <button
              className={styles.btnPrimary}
              onClick={handleGenerateClick}
              disabled={generatingGrid === episode.episodeId || !hasScript}
              title={!hasScript ? '请先在 4A 生成剧本' : ''}
            >
              {generatingGrid === episode.episodeId ? <><SpinIcon /> 排队中...</> : '生成九宫格'}
            </button>
          )}
          {(episode.gridStatus === 'generated' || episode.gridStatus === 'rejected') && (
            <>
              {episode.gridStatus === 'generated' && (
                <button
                  className={styles.btnPrimary}
                  onClick={handleGenerateClick}
                  disabled={isGeneratingThisEpisode}
                >
                  {generatingGrid === episode.episodeId ? <><SpinIcon /> 排队中...</> : '重新生成'}
                </button>
              )}
              <button
                className={styles.btnSuccess}
                onClick={() => onApproveGrid(episode.episodeId)}
                disabled={approvingEpisodeId === episode.episodeId || rejectingEpisodeId === episode.episodeId}
              >
                {approvingEpisodeId === episode.episodeId ? <><SpinIcon /> 审核中...</> : '通过'}
              </button>
              <button
                className={styles.btnDanger}
                onClick={handleRejectClick}
                disabled={approvingEpisodeId === episode.episodeId || rejectingEpisodeId === episode.episodeId}
              >
                {rejectingEpisodeId === episode.episodeId ? <><SpinIcon /> 退回中...</> : '退回'}
              </button>
              {episode.gridStatus === 'rejected' && (
                <button
                  className={styles.btnGhost}
                  onClick={handleRejectToScriptClick}
                  disabled={rejectingEpisodeId === episode.episodeId}
                >
                  退回脚本
                </button>
              )}
            </>
          )}
        </div>
      </div>

      {!hasScript && (
        <div className={styles.gridEmptyState}>
          暂无脚本，请先在 4A 生成剧本
        </div>
      )}

      {/* 多页图片展示 + 每页独立 Prompt 编辑器 */}
      {hasScript && episode.gridImages && episode.gridImages.length > 0 && !isGeneratingThisEpisode && (
        <div className={styles.gridPageSection}>
          {/* 图片分页展示（复用 StoryboardGrid 组件） */}
          <StoryboardGrid
            gridImages={episode.gridImages}
            shots={getAllShots()}
            gridStatus={episode.gridStatus || 'pending'}
            currentPage={currentPage}
            onPageChange={setCurrentPage}
            totalPages={totalPages}
          />

          {/* 可编辑状态时显示当前页的 Prompt 编辑器 */}
          {canEditPrompt && editedPrompts.length > 0 && (
            <div className={styles.gridPromptEditor}>
              <div className={styles.gridPromptEditorHeader}>
                <label className={styles.gridPromptLabel} htmlFor={`grid-prompt-${episode.episodeId}-${currentPage}`}>
                  第 {currentPage} 页图片生成 Prompt（可直接编辑）
                </label>
                {totalPages > 1 && (
                  <span className={styles.gridPromptPageHint}>
                    共 {totalPages} 页，当前第 {currentPage} 页
                  </span>
                )}
              </div>
              <textarea
                id={`grid-prompt-${episode.episodeId}-${currentPage}`}
                className={styles.gridPromptTextarea}
                value={currentPrompt}
                onChange={e => updatePrompt(currentPage - 1, e.target.value)}
                rows={8}
                disabled={isGeneratingThisEpisode}
              />
              <div className={styles.gridPromptTip}>
                直接编辑提示词内容，修改后将使用编辑后的版本重新生成九宫格图片。
                {totalPages > 1 && (
                  <> 提示：可切换页面分别编辑每一页的提示词。</>
                )}
              </div>
            </div>
          )}

          {/* 已通过时显示当前页的 Prompt（只读） */}
          {!canEditPrompt && episode.gridStatus === 'approved' && (
            <div className={styles.gridPromptEditor}>
              <label className={styles.gridPromptLabel}>
                第 {currentPage} 页图片生成 Prompt
              </label>
              <pre className={styles.episodePromptBlock}>
                {currentPrompt || '(暂无提示词)'}
              </pre>
            </div>
          )}
        </div>
      )}

      {/* 还没有生成图片但有脚本数据时：显示提示词编辑器 */}
      {hasScript && (!episode.gridImages || episode.gridImages.length === 0) && !isGeneratingThisEpisode && (
        <div className={styles.gridPromptEditor}>
          <div className={styles.gridPromptEditorHeader}>
            <label className={styles.gridPromptLabel}>
              图片生成 Prompt（可直接编辑）
            </label>
            {editedPrompts.length > 1 && (
              <span className={styles.gridPromptPageHint}>
                共 {editedPrompts.length} 页，当前第 {currentPage} 页
              </span>
            )}
          </div>
          {editedPrompts.length > 1 && (
            <div className={styles.gridPageTabs}>
              {editedPrompts.map((_, idx) => (
                <button
                  key={idx}
                  className={`${styles.gridPageTab} ${currentPage === idx + 1 ? styles.gridPageTabActive : ''}`}
                  onClick={() => setCurrentPage(idx + 1)}
                >
                  第{idx + 1}页
                </button>
              ))}
            </div>
          )}
          <textarea
            id={`grid-prompt-${episode.episodeId}-${currentPage}`}
            className={styles.gridPromptTextarea}
            value={currentPrompt}
            onChange={e => updatePrompt(currentPage - 1, e.target.value)}
            rows={8}
            disabled={generatingGrid === episode.episodeId}
          />
          <div className={styles.gridPromptTip}>
            直接编辑提示词内容，修改后将使用编辑后的版本重新生成九宫格图片。
          </div>
        </div>
      )}

      {hasScript && (!episode.gridImages || episode.gridImages.length === 0) && (
        <div className={styles.gridEmptyState}>
          暂无九宫格图片
        </div>
      )}
    </div>
  );
});

export default GridEpisodeCard;
