import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { EpisodeState } from '../types';
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
  onGenerateGrid: (episodeId: number, fullPrompt?: string) => void;
  onApproveGrid: (episodeId: number) => void;
  onRejectGrid: (episodeId: number, reason: string) => void;
  onRejectToScript: (episodeId: number) => void;
  onOpenLightbox: (url: string) => void;
  buildGridPromptText?: (visualStyle: string, shots: any[], isComicCommentary?: boolean) => string;
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
  const [editedPrompt, setEditedPrompt] = useState('');
  // 追踪用户是否手动编辑过 prompt，避免 SSE/polling 更新时覆盖用户编辑
  const hasLocalEditRef = useRef(false);

  // 是否可以编辑 prompt（approved 和 generating 状态不可编辑）
  const canEditPrompt = episode.gridStatus !== 'approved' && episode.gridStatus !== 'generating';

  // 是否有脚本数据
  const hasScript = episode.segments.length > 0 || (episode.episodeInfo?.shots?.length ?? 0) > 0;

  // 当 episode ID 或 gridStatus 变化时，重置 editedPrompt（用户手动编辑时不重置）
  useEffect(() => {
    hasLocalEditRef.current = false;
    // 优先使用 segments 数据， fallback 到 episodeInfo.shots
    const shotsData = episode.segments.length > 0
      ? episode.segments
      : (episode.episodeInfo?.shots || []);
    const allShots = shotsData.map((s: any) => s.shots?.[0] || s).filter(Boolean);
    const visualStyle = episode.segments[0]?.panelData?.visualStyle
      || (episode.episodeInfo?.visualStyle as string)
      || 'ANIME';

    if (canEditPrompt && buildGridPromptText && allShots.length > 0) {
      const generated = buildGridPromptText(visualStyle, allShots, false);
      // 如果有保存的 gridPrompt，用它；否则用自动生成的
      setEditedPrompt(episode.gridPrompt || generated);
    } else {
      setEditedPrompt('');
    }
  }, [episode.episodeId, episode.gridStatus]);

  // gridPrompt 从后端加载后同步（仅在用户未手动编辑时）
  useEffect(() => {
    if (hasLocalEditRef.current) return;
    if (episode.gridPrompt && canEditPrompt) {
      setEditedPrompt(episode.gridPrompt);
    }
  }, [episode.gridPrompt]);

  const handleRejectClick = useCallback(() => {
    const reason = prompt('请给出你的优化建议:');
    if (reason) onRejectGrid(episode.episodeId, reason);
  }, [episode.episodeId, onRejectGrid]);

  const handleGenerateClick = useCallback(() => {
    // pending、generated、rejected 状态如果有编辑过的 prompt，传 fullPrompt
    if (canEditPrompt && editedPrompt) {
      onGenerateGrid(episode.episodeId, editedPrompt);
    } else {
      onGenerateGrid(episode.episodeId);
    }
  }, [episode.episodeId, onGenerateGrid, editedPrompt, canEditPrompt]);

  const handleRejectToScriptClick = useCallback(() => {
    if (confirm('确定要退回脚本阶段吗？九宫格数据将被清除。')) {
      onRejectToScript(episode.episodeId);
    }
  }, [episode.episodeId, onRejectToScript]);

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
                  disabled={generatingGrid === episode.episodeId}
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

      {/* 可编辑状态时显示完整提示词编辑器 */}
      {hasScript && canEditPrompt && editedPrompt && (
        <div className={styles.gridPromptEditor}>
          <label className={styles.gridPromptLabel} htmlFor={`grid-prompt-${episode.episodeId}`}>
            图片生成 Prompt（可直接编辑）
          </label>
          <textarea
            id={`grid-prompt-${episode.episodeId}`}
            className={styles.gridPromptTextarea}
            value={editedPrompt}
            onChange={e => { hasLocalEditRef.current = true; setEditedPrompt(e.target.value); }}
            rows={8}
            disabled={generatingGrid === episode.episodeId}
          />
          <div className={styles.gridPromptTip}>
            直接编辑提示词内容，修改后将使用编辑后的版本重新生成九宫格图片。
          </div>
        </div>
      )}

      {/* 已通过时显示提示词（只读） */}
      {hasScript && episode.gridStatus === 'approved' && episode.gridPrompt && (
        <div className={styles.gridPromptEditor}>
          <label className={styles.gridPromptLabel}>
            图片生成 Prompt
          </label>
          <pre className={styles.episodePromptBlock}>
            {episode.gridPrompt}
          </pre>
        </div>
      )}

      {hasScript && episode.gridImages && episode.gridImages.length > 0 && (
        <div className={styles.gridImagesContainer}>
          {episode.gridImages.map((url, idx) => (
            <img
              key={idx}
              src={url}
              alt={`九宫格 ${idx + 1}`}
              className={styles.gridImage}
              onClick={() => onOpenLightbox(url)}
            />
          ))}
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
