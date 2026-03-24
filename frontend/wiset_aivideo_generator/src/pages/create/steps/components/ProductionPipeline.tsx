import styles from './ProductionPipeline.module.less';
import type { ProductionStage, PanelStageStatus } from '../../../../services/types/episode.types';

interface StageInfo {
  key: ProductionStage;
  label: string;
}

const STAGES: StageInfo[] = [
  { key: 'background', label: '背景图' },
  { key: 'fusion', label: '融合图' },
  { key: 'transition', label: '过渡融合图' },
  { key: 'video', label: '视频' },
];

interface ProductionPipelineProps {
  currentStage: ProductionStage;
  stageStatuses: Record<ProductionStage, PanelStageStatus>;
}

export default function ProductionPipeline({ currentStage, stageStatuses }: ProductionPipelineProps) {
  const currentIndex = STAGES.findIndex(s => s.key === currentStage);

  return (
    <div className={styles.pipeline}>
      {STAGES.map((stage, idx) => {
        const status = stageStatuses[stage.key];
        const isDone = status === 'completed';
        const isActive = idx === currentIndex && status !== 'completed';
        const isFailed = status === 'failed';
        const isPending = status === 'pending';

        return (
          <div key={stage.key} className={styles.stage}>
            <div className={`${styles.dot} ${isDone ? styles.done : ''} ${isActive ? styles.active : ''} ${isFailed ? styles.failed : ''}`}>
              {isDone ? '✓' : isFailed ? '!' : idx + 1}
            </div>
            <span className={`${styles.label} ${isActive ? styles.activeLabel : ''} ${isPending ? styles.pendingLabel : ''}`}>
              {stage.label}
            </span>
            {idx < STAGES.length - 1 && (
              <div className={`${styles.connector} ${isDone ? styles.connectorDone : ''}`} />
            )}
          </div>
        );
      })}
    </div>
  );
}
