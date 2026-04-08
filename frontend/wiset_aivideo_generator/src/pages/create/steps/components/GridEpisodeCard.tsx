import React, { useCallback, useEffect, useState } from 'react';
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
  onGenerateGrid: (episodeId: number, customHint?: string) => void;
  onApproveGrid: (episodeId: number) => void;
  onRejectGrid: (episodeId: number, reason: string) => void;
  onRejectToScript: (episodeId: number) => void;
  onOpenLightbox: (url: string) => void;
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
}: GridEpisodeCardProps) {
  const badge = getGridStatusBadge(episode.gridStatus || 'pending');
  const [customHint, setCustomHint] = useState(episode.gridPromptHint || '');

  useEffect(() => {
    setCustomHint(episode.gridPromptHint || '');
  }, [episode.episodeId, episode.gridPromptHint]);

  const handleRejectClick = useCallback(() => {
    const reason = prompt('请给出你的优化建议:');
    if (reason) onRejectGrid(episode.episodeId, reason);
  }, [episode.episodeId, onRejectGrid]);

  const handleGenerateClick = useCallback(() => {
    onGenerateGrid(episode.episodeId, customHint);
  }, [episode.episodeId, onGenerateGrid, customHint]);

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
              disabled={generatingGrid === episode.episodeId}
            >
              {generatingGrid === episode.episodeId ? <><SpinIcon /> 排队中...</> : '生成九宫格'}
            </button>
          )}
          {(episode.gridStatus === 'generated' || episode.gridStatus === 'rejected') && (
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
              <button
                className={styles.btnGhost}
                onClick={handleRejectToScriptClick}
                disabled={rejectingEpisodeId === episode.episodeId}
              >
                退回脚本
              </button>
            </>
          )}
        </div>
      </div>

      <div className={styles.gridPromptEditor}>
        <label className={styles.gridPromptLabel} htmlFor={`grid-hint-${episode.episodeId}`}>
          图片生成提示补充（4B 可选）
        </label>
        <textarea
          id={`grid-hint-${episode.episodeId}`}
          className={styles.gridPromptTextarea}
          value={customHint}
          onChange={e => setCustomHint(e.target.value)}
          placeholder="例如：强调雨夜氛围、镜头低机位、人物面部更清晰"
          rows={3}
          maxLength={500}
          disabled={generatingGrid === episode.episodeId}
        />
        <div className={styles.gridPromptTip}>
          会追加到本集九宫格图片生成提示词中，用于微调风格、构图和氛围。
        </div>
      </div>

      {episode.gridImages && episode.gridImages.length > 0 && (
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
      {(!episode.gridImages || episode.gridImages.length === 0) && (
        <div className={styles.gridEmptyState}>
          暂无九宫格图片
        </div>
      )}
    </div>
  );
});

export default GridEpisodeCard;
