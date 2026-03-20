import styles from './ProductionProgress.module.less';

interface ProductionProgressProps {
  progressPercent: number;
  progressMessage: string;
  currentStage: string;
  totalPanels?: number;
  completedPanels?: number;
}

const ProductionProgress = ({ progressPercent, progressMessage, currentStage, totalPanels, completedPanels }: ProductionProgressProps) => {
  return (
    <div className={styles.progressView}>
      <span className={styles.progressStage}>{currentStage}</span>
      <p className={styles.progressMessage}>{progressMessage}</p>
      <div className={styles.progressBarContainer}>
        <div className={styles.progressBarTrack}>
          <div className={styles.progressBarFill} style={{ width: `${progressPercent}%` }} />
        </div>
        <div className={styles.progressInfo}>
          <span className={styles.progressPercent}>{progressPercent}%</span>
          {totalPanels != null && totalPanels > 0 && (
            <span className={styles.progressDetail}>
              {completedPanels ?? 0}/{totalPanels} 面板
            </span>
          )}
        </div>
      </div>
    </div>
  );
};

export default ProductionProgress;
