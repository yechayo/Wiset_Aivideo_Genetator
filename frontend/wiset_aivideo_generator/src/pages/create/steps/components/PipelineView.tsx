import styles from './PipelineView.module.less';
import type { PipelineStage, StageDisplayStatus } from '../../../../services/types/episode.types';

interface PipelineViewProps {
  stages: PipelineStage[];
  onFusionClick?: () => void;
  onRetryClick?: () => void;
}

const STATUS_ICON: Record<StageDisplayStatus, string> = {
  completed: '\u2705',
  active: '\uD83D\uDD04',
  waiting_user: '\uD83D\uDC64',
  pending: '\u2B1C',
  failed: '\u274C',
};

const STATUS_LABEL: Record<StageDisplayStatus, string> = {
  completed: '已完成',
  active: '处理中',
  waiting_user: '等待操作',
  pending: '等待中',
  failed: '失败',
};

const PipelineView = ({ stages, onFusionClick, onRetryClick }: PipelineViewProps) => {
  const failedStage = stages.find((s) => s.displayStatus === 'failed');

  return (
    <div className={styles.pipelineView}>
      <div className={styles.stageList}>
        {stages.map((stage) => (
          <div
            key={stage.key}
            className={`${styles.stageRow} ${styles[stage.displayStatus]}`}
          >
            <div className={styles.stageLeft}>
              <span className={styles.stageIcon}>{STATUS_ICON[stage.displayStatus]}</span>
              {stage.displayStatus === 'active' && (
                <span className={styles.activePulse} />
              )}
            </div>
            <div className={styles.stageContent}>
              <span className={styles.stageName}>{stage.name}</span>
              {stage.displayStatus === 'active' && (
                <div className={styles.miniProgressBar}>
                  <div
                    className={styles.miniProgressFill}
                    style={{ width: `${stage.progress}%` }}
                  />
                </div>
              )}
            </div>
            <div className={styles.stageRight}>
              <span className={styles.stageStatus}>{STATUS_LABEL[stage.displayStatus]}</span>
              {stage.displayStatus === 'waiting_user' && stage.key === 'grid_fusion' && (
                <button className={styles.actionButton} onClick={onFusionClick}>
                  进入融合编辑
                </button>
              )}
              {stage.displayStatus === 'failed' && (
                <button className={styles.actionButton} onClick={onRetryClick}>
                  重试
                </button>
              )}
            </div>
          </div>
        ))}
      </div>

      {failedStage && failedStage.message && (
        <div className={styles.errorMessage}>
          {failedStage.message}
        </div>
      )}
    </div>
  );
};

export default PipelineView;
