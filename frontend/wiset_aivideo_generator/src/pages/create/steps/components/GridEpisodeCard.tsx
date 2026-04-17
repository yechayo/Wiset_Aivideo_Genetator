import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { EpisodeState } from '../types';
import { StoryboardGrid } from './StoryboardGrid';
import { getCharacterReferences } from '../../../../services/episodeService';
import styles from '../Step4Production.module.less';

const ROLE_LABEL: Record<string, string> = {
  主角: '主角',
  反派: '反派',
  配角: '配角',
};

const SpinIcon = () => <span className={styles.btnSpinner} />;

function getGridStatusBadge(status: string) {
  switch (status) {
    case 'approved': return { text: '已通过', className: styles.statusApproved };
    case 'generated': return { text: '待审核', className: styles.statusGenerated };
    case 'rejected': return { text: '已退回', className: styles.statusRejected };
    case 'generating': return { text: '生成中', className: styles.statusGenerating, pulse: true };
    default: return { text: '待生成', className: styles.statusPending };
  }
}

interface GridEpisodeCardProps {
  projectId: string;
  episode: EpisodeState;
  generatingGrid: number | null;
  /** 逐页生成中的页码集合 */
  generatingPages?: Set<number>;
  approvingEpisodeId: number | null;
  rejectingEpisodeId: number | null;
  /** 支持多页 prompts: 如果有值则传多页,否则传单页 fullPrompt */
  onGenerateGrid: (episodeId: number, fullPrompt?: string, gridPrompts?: string[]) => void;
  /** 逐页生成回调 */
  onGenerateGridPage?: (episodeId: number, pageIndex: number, prompt?: string) => void;
  onApproveGrid: (episodeId: number) => void;
  onRejectGrid: (episodeId: number, reason: string) => void;
  onRejectToScript: (episodeId: number) => void;
  onOpenLightbox: (url: string) => void;
  buildGridPromptText?: (visualStyle: string, shots: any[], isComicCommentary?: boolean, gridCols?: number, gridRows?: number) => string;
}

/** 根据分镜数量计算网格布局（与后端一致） */
function getGridSize(shotCount: number): { cols: number; rows: number } {
  if (shotCount <= 4) return { cols: 2, rows: 2 };
  if (shotCount <= 9) return { cols: 3, rows: 3 };
  if (shotCount <= 16) return { cols: 4, rows: 4 };
  return { cols: 5, rows: 5 };
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

/** 获取某页的 shots */
function getPageShots(allShots: any[], pageIndex: number, gridConfigs?: any[]): any[] {
  if (!allShots.length) return [];

  if (gridConfigs && gridConfigs.length > pageIndex) {
    let fromIdx = 0;
    for (let i = 0; i < pageIndex; i++) {
      fromIdx += gridConfigs[i].shotCount ?? (gridConfigs[i].gridCols * gridConfigs[i].gridRows);
    }
    const config = gridConfigs[pageIndex];
    const capacity = config.shotCount ?? (config.gridCols * config.gridRows);
    return allShots.slice(fromIdx, fromIdx + capacity);
  }

  const pages = buildAdaptivePages(allShots.length);
  const page = pages[pageIndex];
  if (!page) return [];
  return allShots.slice(page.fromIdx, page.toIdx);
}

const GridEpisodeCard = React.memo(function GridEpisodeCard({
  projectId,
  episode,
  generatingGrid,
  generatingPages,
  approvingEpisodeId,
  rejectingEpisodeId,
  onGenerateGrid,
  onGenerateGridPage,
  onApproveGrid,
  onRejectGrid,
  onRejectToScript,
  onOpenLightbox,
  buildGridPromptText,
}: GridEpisodeCardProps) {
  const isApproved = episode.gridStatus === 'approved';
  const badge = getGridStatusBadge(episode.gridStatus || 'pending');
  const [editedPrompts, setEditedPrompts] = useState<string[]>([]);
  const [currentPage, setCurrentPage] = useState(1);
  const hasLocalEditRef = useRef(false);

  const canEditPrompt = episode.gridStatus !== 'approved' && episode.gridStatus !== 'generating';
  const isGeneratingThisEpisode = generatingGrid === episode.episodeId;
  const hasScript = episode.segments.length > 0 || (episode.episodeInfo?.shots?.length ?? 0) > 0;

  // 获取所有 shots 数据
  const getAllShots = useCallback(() => {
    const shotsData = episode.segments.length > 0
      ? episode.segments
      : (episode.episodeInfo?.shots || []);
    return shotsData.map((s: any) => s.shots?.[0] || s).filter(Boolean);
  }, [episode.segments, episode.episodeInfo?.shots]);

  const totalPages = episode.gridImages?.length || buildAdaptivePages(getAllShots().length).length || 1;

  // 当前页的 shots
  const currentPageShots = React.useMemo(() => {
    const allShots = getAllShots();
    return getPageShots(allShots, currentPage - 1, (episode as any).gridConfigs);
  }, [getAllShots, currentPage, (episode as any).gridConfigs]);

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
    let cols: number, rows: number;
    if (config) {
      cols = config.gridCols;
      rows = config.gridRows;
    } else {
      const adaptivePages = buildAdaptivePages(allShots.length);
      const pageConfig = adaptivePages[pageIndex];
      cols = pageConfig?.cols ?? 3;
      rows = pageConfig?.rows ?? 3;
    }
    const pageShots = getPageShots(allShots, pageIndex, (episode as any).gridConfigs);
    return buildGridPromptText(visualStyle, pageShots, false, cols, rows);
  }, [buildGridPromptText, getAllShots, getVisualStyle, episode]);

  // 初始化/重置 editedPrompts
  useEffect(() => {
    hasLocalEditRef.current = false;
    const allShots = getAllShots();
    const pageCount = buildAdaptivePages(allShots.length).length || 1;

    if (canEditPrompt && buildGridPromptText && allShots.length > 0) {
      if (episode.gridPrompts && episode.gridPrompts.length > 0) {
        setEditedPrompts(episode.gridPrompts);
      } else if (episode.gridPrompt) {
        const prompts: string[] = [];
        for (let i = 0; i < pageCount; i++) {
          prompts.push(i === 0 ? episode.gridPrompt : buildPagePrompt(i));
        }
        setEditedPrompts(prompts);
      } else {
        const prompts: string[] = [];
        for (let i = 0; i < pageCount; i++) {
          prompts.push(buildPagePrompt(i));
        }
        setEditedPrompts(prompts);
      }
    } else {
      // 已通过时也初始化 prompts 用于只读展示
      if (episode.gridPrompts && episode.gridPrompts.length > 0) {
        setEditedPrompts(episode.gridPrompts);
      } else if (episode.gridPrompt) {
        const allShots = getAllShots();
        const pageCount = buildAdaptivePages(allShots.length).length || 1;
        const prompts: string[] = [];
        for (let i = 0; i < pageCount; i++) {
          prompts.push(i === 0 ? episode.gridPrompt! : buildPagePrompt(i));
        }
        setEditedPrompts(prompts);
      }
    }
  }, [episode.episodeId, episode.gridStatus, episode.gridPrompts, episode.gridPrompt]);

  // gridPrompt / gridPrompts 从后端加载后同步
  useEffect(() => {
    if (hasLocalEditRef.current) return;
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
  }, [episode.gridPrompt, episode.gridPrompts, getAllShots, buildPagePrompt]);

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
    if (reason !== null) onRejectGrid(episode.episodeId, reason || '无');
  }, [episode.episodeId, onRejectGrid]);

  const handleGenerateAllClick = useCallback(() => {
    if (hasLocalEditRef.current && editedPrompts.length > 0) {
      onGenerateGrid(episode.episodeId, editedPrompts[0], editedPrompts);
    } else {
      onGenerateGrid(episode.episodeId);
    }
  }, [episode.episodeId, onGenerateGrid, editedPrompts]);

  const handleGeneratePageClick = useCallback((pageIndex: number) => {
    const prompt = hasLocalEditRef.current ? editedPrompts[pageIndex] : undefined;
    onGenerateGridPage?.(episode.episodeId, pageIndex, prompt);
  }, [episode.episodeId, onGenerateGridPage, editedPrompts]);

  const handleRejectToScriptClick = useCallback(() => {
    if (confirm('确定要退回脚本阶段吗？宫格数据将被清除。')) {
      onRejectToScript(episode.episodeId);
    }
  }, [episode.episodeId, onRejectToScript]);

  const currentPrompt = editedPrompts[currentPage - 1] || '';
  const hasImageForCurrentPage = !!(episode.gridImages?.[currentPage - 1]);

  // 角色参考图
  const [charRefs, setCharRefs] = useState<{ name: string; url: string; role: string }[]>(
    episode.characterReferences?.filter(c => c.url) || [],
  );
  const charRefsFetchedRef = useRef(false);

  useEffect(() => {
    if (charRefsFetchedRef.current) return;
    if (!hasScript) return;
    if (episode.characterReferences && episode.characterReferences.length > 0) return;
    charRefsFetchedRef.current = true;
    let cancelled = false;
    getCharacterReferences(projectId, episode.episodeId)
      .then(res => {
        if (!cancelled && res.data?.data) {
          const list = res.data.data.filter(c => c.url);
          if (list.length > 0) setCharRefs(list);
        }
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [projectId, episode.episodeId, episode.characterReferences, hasScript]);

  // 判断是否所有页都生成了图片
  const allPagesGenerated = React.useMemo(() => {
    const allShots = getAllShots();
    const pages = buildAdaptivePages(allShots.length);
    if (pages.length === 0) return false;
    return pages.every((_, idx) => !!episode.gridImages?.[idx]);
  }, [getAllShots, episode.gridImages]);

  return (
    <div className={`${styles.episodeGridCard} ${isApproved ? styles.episodeCardDone : ''}`}>
      <div className={styles.episodeGridHeader}>
        <div>
          <h3 className={styles.episodeGridTitle}>
            第{episode.episodeIndex}集 {episode.title}
            {isApproved && <span className={styles.episodeCardDoneLabel}>✓ 已通过</span>}
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
          {/* 未生成时：全部生成 */}
          {!isApproved && !allPagesGenerated && !isGeneratingThisEpisode && (
            <button
              className={styles.btnPrimary}
              onClick={handleGenerateAllClick}
              disabled={!hasScript}
              title={!hasScript ? '请先在 4A 生成剧本' : ''}
            >
              全部生成
            </button>
          )}
          {/* 已生成（全部页都有图）且未通过：通过/退回 */}
          {(episode.gridStatus === 'generated' || allPagesGenerated) && !isApproved && (
            <>
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
            </>
          )}
          {/* 已退回：退回脚本 */}
          {episode.gridStatus === 'rejected' && (
            <button
              className={styles.btnGhost}
              onClick={handleRejectToScriptClick}
              disabled={rejectingEpisodeId === episode.episodeId}
            >
              退回脚本
            </button>
          )}
          {/* 已通过：可退回重新编辑 */}
          {isApproved && (
            <button
              className={styles.btnDanger}
              onClick={handleRejectClick}
              disabled={rejectingEpisodeId === episode.episodeId}
            >
              {rejectingEpisodeId === episode.episodeId ? <><SpinIcon /> 退回中...</> : '退回'}
            </button>
          )}
        </div>
      </div>

      {!hasScript && (
        <div className={styles.gridEmptyState}>
          暂无脚本，请先在 4A 生成剧本
        </div>
      )}

      {/* 分页 tab + 右侧逐页生成按钮 */}
      {hasScript && totalPages > 1 && (
        <div className={styles.gridPageBar}>
          <div className={styles.gridPageTabs}>
            {Array.from({ length: totalPages }, (_, idx) => {
              const hasImage = !!episode.gridImages?.[idx];
              return (
                <button
                  key={idx}
                  className={`${styles.gridPageTab} ${currentPage === idx + 1 ? styles.gridPageTabActive : ''} ${hasImage ? styles.gridPageTabDone : ''}`}
                  onClick={() => setCurrentPage(idx + 1)}
                >
                  第{idx + 1}页
                  {hasImage && <span className={styles.gridPageTabDot} />}
                </button>
              );
            })}
          </div>
          {/* 右侧：当前页生成/重新生成 */}
          {!isApproved && onGenerateGridPage && (
            <button
              className={styles.gridPageGenBtn}
              onClick={() => handleGeneratePageClick(currentPage - 1)}
              disabled={generatingPages?.has(currentPage - 1) || isGeneratingThisEpisode}
            >
              {generatingPages?.has(currentPage - 1) ? <><SpinIcon /> 生成中...</> : (episode.gridImages?.[currentPage - 1] ? '重新生成此页' : '生成此页')}
            </button>
          )}
        </div>
      )}

      {/* 单页：右侧生成按钮 */}
      {hasScript && totalPages <= 1 && !isApproved && onGenerateGridPage && (
        <div className={styles.gridPageBar}>
          <div />
          <button
            className={styles.gridPageGenBtn}
            onClick={() => handleGeneratePageClick(0)}
            disabled={isGeneratingThisEpisode || generatingPages?.has(0)}
          >
            {(isGeneratingThisEpisode || generatingPages?.has(0)) ? <><SpinIcon /> 生成中...</> : (hasImageForCurrentPage ? '重新生成' : '生成宫格图')}
          </button>
        </div>
      )}

      {/* 宫格图片展示 */}
      {hasScript && episode.gridImages && episode.gridImages.length > 0 && !isGeneratingThisEpisode && (
        <div className={styles.gridPageSection}>
          <StoryboardGrid
            gridImages={episode.gridImages}
            shots={currentPageShots}
            gridStatus={episode.gridStatus || 'pending'}
            currentPage={currentPage}
            onPageChange={setCurrentPage}
            totalPages={episode.gridImages.length}
          />

          {/* Prompt 显示/编辑 */}
          {canEditPrompt && editedPrompts.length > 0 && (
            <div className={styles.gridPromptEditor}>
              <div className={styles.gridPromptEditorHeader}>
                <label className={styles.gridPromptLabel} htmlFor={`grid-prompt-${episode.episodeId}-${currentPage}`}>
                  第 {currentPage} 页图片生成 Prompt
                </label>
              </div>
              {charRefs.length > 0 && (
                <div className={styles.gridCharRefs}>
                  <span className={styles.gridCharRefsLabel}>角色参考图</span>
                  <div className={styles.gridCharRefList}>
                    {charRefs.map((cr, idx) => (
                      <div key={idx} className={styles.gridCharRefItem} onClick={() => onOpenLightbox(cr.url)}>
                        <img src={cr.url} alt={cr.name} />
                        <span className={styles.gridCharRefName}>{cr.name}</span>
                        {cr.role && <span className={styles.gridCharRefRole}>{ROLE_LABEL[cr.role] || cr.role}</span>}
                      </div>
                    ))}
                  </div>
                </div>
              )}
              <textarea
                id={`grid-prompt-${episode.episodeId}-${currentPage}`}
                className={styles.gridPromptTextarea}
                value={currentPrompt}
                onChange={e => updatePrompt(currentPage - 1, e.target.value)}
                rows={8}
                disabled={isGeneratingThisEpisode}
              />
              <div className={styles.gridPromptTip}>
                直接编辑提示词内容，修改后点击该页的生成按钮使用编辑后的版本。
              </div>
            </div>
          )}

          {/* 已通过时只读 prompt */}
          {isApproved && editedPrompts.length > 0 && (
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

      {/* 没有图片但有脚本：显示 prompt 编辑器 */}
      {hasScript && (!episode.gridImages || episode.gridImages.length === 0) && !isGeneratingThisEpisode && (
        <div className={styles.gridPromptEditor}>
          <div className={styles.gridPromptEditorHeader}>
            <label className={styles.gridPromptLabel}>
              图片生成 Prompt（可直接编辑）
            </label>
          </div>
          {charRefs.length > 0 && (
            <div className={styles.gridCharRefs}>
              <span className={styles.gridCharRefsLabel}>角色参考图</span>
              <div className={styles.gridCharRefList}>
                {charRefs.map((cr, idx) => (
                  <div key={idx} className={styles.gridCharRefItem} onClick={() => onOpenLightbox(cr.url)}>
                    <img src={cr.url} alt={cr.name} />
                    <span className={styles.gridCharRefName}>{cr.name}</span>
                    {cr.role && <span className={styles.gridCharRefRole}>{ROLE_LABEL[cr.role] || cr.role}</span>}
                  </div>
                ))}
              </div>
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
            直接编辑提示词内容，修改后点击生成按钮使用编辑后的版本。
          </div>
        </div>
      )}

      {hasScript && (!episode.gridImages || episode.gridImages.length === 0) && !isGeneratingThisEpisode && (
        <div className={styles.gridEmptyState}>
          暂无宫格图片
        </div>
      )}
    </div>
  );
});

export default GridEpisodeCard;
