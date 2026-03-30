import React from 'react';
import styles from './BatchReviewBar.module.less';

export interface BatchReviewBarProps {
  totalPanels: number;
  approvedCount: number;
  pendingReviewCount: number;
  onApproveAll: () => void;
}

export const BatchReviewBar: React.FC<BatchReviewBarProps> = ({
  totalPanels,
  approvedCount,
  pendingReviewCount,
  onApproveAll,
}) => {
  const progressPercent = totalPanels > 0 ? Math.round((approvedCount / totalPanels) * 100) : 0;

  return (
    <div className={styles.batchReviewBar}>
      <div className={styles.progressSection}>
        <div className={styles.progressBar}>
          <div className={styles.progressFill} style={{ width: `${progressPercent}%` }} />
        </div>
        <div className={styles.statsText}>
          <span className={styles.approved}>已审核 {approvedCount}</span>
          <span className={styles.separator}>/</span>
          <span className={styles.total}>共 {totalPanels} 个</span>
          {pendingReviewCount > 0 && (
            <>
              <span className={styles.separator}>|</span>
              <span className={styles.pending}>{pendingReviewCount} 个待审核</span>
            </>
          )}
        </div>
      </div>
      <button
        className={styles.approveAllButton}
        onClick={onApproveAll}
        disabled={pendingReviewCount === 0}
      >
        一键全部通过
      </button>
    </div>
  );
};
