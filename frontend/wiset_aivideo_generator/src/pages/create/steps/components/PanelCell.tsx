import styles from './PanelCell.module.less';
import type { PanelState } from '../../../../services/types/episode.types';

interface PanelCellProps {
  panel: PanelState;
  onGenerateVideo?: (panelIndex: number) => void;
  isGenerating?: boolean;
}

const PanelCell = ({ panel, onGenerateVideo, isGenerating = false }: PanelCellProps) => {
  const statusLabels = {
    pending: '待生成',
    generating: '生成中',
    completed: '已完成',
    failed: '失败',
  };

  const statusClass = {
    pending: styles.pending,
    generating: styles.generating,
    completed: styles.completed,
    failed: styles.failed,
  }[panel.videoStatus];

  const canRetry = panel.videoStatus === 'failed' && onGenerateVideo;
  const showFusion = panel.fusionStatus === 'completed' && panel.fusionUrl;
  const showVideo = panel.videoStatus === 'completed' && panel.videoUrl;

  return (
    <div className={styles.cell}>
      {/* Header */}
      <div className={styles.header}>
        <span className={styles.index}>#{panel.panelIndex + 1}</span>
        <span className={`${styles.status} ${statusClass}`}>
          {statusLabels[panel.videoStatus]}
        </span>
      </div>

      {/* Content */}
      <div className={styles.content}>
        {showVideo && (
          <div className={styles.videoWrapper}>
            <video src={panel.videoUrl} controls preload="metadata" />
          </div>
        ) else if (showFusion && !showVideo && (
          <div className={styles.fusionWrapper}>
            <img src={panel.fusionUrl} alt={`Panel ${panel.panelIndex}`} />
            {panel.promptText && (
              <div className={styles.promptTooltip}>
                {panel.promptText}
              </div>
            )}
          </div>
        )} else if (panel.videoStatus === 'generating' && (
          <div className={styles.generatingState}>
            <div className={styles.spinner} />
            <p>视频生成中...</p>
          </div>
        )} else if (panel.videoStatus === 'pending' && (
          <div className={styles.pendingState}>
            <p>等待生成</p>
          </div>
        )} else if (panel.videoStatus === 'failed' && (
          <div className={styles.failedState}>
            <span className={styles.errorIcon}>⚠</span>
            <p>生成失败</p>
          </div        )}
      </div>

      {/* Footer Info */}
      <div className={styles.footer}>
        {panel.sceneDescription && (
          <div className={styles.sceneInfo}>
            <span className={styles.label}>场景:</span>
            <span className={styles.value}>{panel.sceneDescription}</span>
          </div>
        )}
        {panel.shotType && (
          <div className={styles.shotInfo}>
            <span className={styles.label}>景别:</span>
            <span className={styles.value}>{panel.shotType}</span>
          </div>
        )}
        {panel.dialogue && (
          <div className={styles.dialogueInfo}>
            <span className={styles.label}>对白:</span>
            <span className={styles.value}>{panel.dialogue}</span>
          </div>
        )}
      </div>

      {/* Actions */}
      {canRetry && (
        <div className={styles.actions}>
          <button
            className={styles.retryBtn}
            onClick={() => onGenerateVideo!(panel.panelIndex)}
            disabled={isGenerating}
          >
            重试
          </button>
        </div>
      )}
    </div>
  );
};

export default PanelCell;
