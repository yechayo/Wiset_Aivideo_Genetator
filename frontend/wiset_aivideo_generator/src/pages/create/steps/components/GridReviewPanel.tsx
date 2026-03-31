import React, { useState } from 'react';
import styles from './GridReviewPanel.module.less';
import { StoryboardGrid } from './StoryboardGrid';
import type { StoryboardShot } from '../../../../services/types/episode.types';

export interface GridReviewPanelProps {
  panelId: number;
  gridImages: string[];
  shots: StoryboardShot[];
  gridStatus: string;
  gridRejectionFeedback: string | null;
  onApprove: () => void;
  onReject: (reason: string) => void;
  onRegenerate: () => void;
  isRegenerating?: boolean;
}

export const GridReviewPanel: React.FC<GridReviewPanelProps> = ({
  panelId: _panelId,
  gridImages,
  shots,
  gridStatus,
  gridRejectionFeedback,
  onApprove,
  onReject,
  onRegenerate,
  isRegenerating,
}) => {
  const [currentPage, setCurrentPage] = useState(1);
  const [rejectReason, setRejectReason] = useState('');
  const [showRejectInput, setShowRejectInput] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const totalPages = gridImages.length || 1;
  const isApproved = gridStatus === 'approved';
  const isReviewable = gridStatus === 'generated' || gridStatus === 'rejected';

  const handleApprove = async () => {
    if (isSubmitting) return;
    setIsSubmitting(true);
    try {
      await onApprove();
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleReject = async () => {
    if (isSubmitting) return;
    setIsSubmitting(true);
    try {
      await onReject(rejectReason);
      setRejectReason('');
      setShowRejectInput(false);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleRegenerate = async () => {
    if (isSubmitting) return;
    setIsSubmitting(true);
    try {
      await onRegenerate();
    } finally {
      setIsSubmitting(false);
    }
  };

  const renderStatusBadge = () => {
    if (isApproved) {
      return (
        <div className={styles.statusBadge} style={{ backgroundColor: '#4ade80' }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <path d="M20 6L9 17l-5-5" />
          </svg>
          <span>已通过</span>
        </div>
      );
    }
    if (isRegenerating || gridStatus === 'generating') {
      return (
        <div className={styles.statusBadge} style={{ backgroundColor: '#fbbf24' }}>
          <span className={styles.miniSpinner} />
          <span>生成中</span>
        </div>
      );
    }
    if (gridStatus === 'failed') {
      return (
        <div className={styles.statusBadge} style={{ backgroundColor: '#f2777b' }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <circle cx="12" cy="12" r="10" />
            <path d="M15 9l-6 6M9 9l6 6" />
          </svg>
          <span>失败</span>
        </div>
      );
    }
    if (isReviewable && gridImages.length > 0) {
      return (
        <div className={styles.statusBadge} style={{ backgroundColor: '#fbbf24' }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <circle cx="12" cy="12" r="10" fill="none" />
            <path d="M12 6v6l4 2" />
          </svg>
          <span>待审核</span>
        </div>
      );
    }
    if (gridRejectionFeedback) {
      return (
        <div className={styles.statusBadge} style={{ backgroundColor: '#f2777b' }}>
          <span>已退回</span>
        </div>
      );
    }
    return null;
  };

  return (
    <div className={styles.gridReviewPanel}>
      <div className={styles.header}>
        <h3 className={styles.title}>九宫格分镜</h3>
        {renderStatusBadge()}
      </div>

      <div className={styles.content}>
        {gridRejectionFeedback && (
          <div className={styles.rejectionNote}>
            <span className={styles.rejectionLabel}>退回原因：</span>
            <span>{gridRejectionFeedback}</span>
          </div>
        )}

        <StoryboardGrid
          gridImages={gridImages}
          shots={shots}
          gridStatus={gridStatus}
          currentPage={currentPage}
          onPageChange={setCurrentPage}
          totalPages={totalPages}
        />

        {/* Action buttons */}
        {isReviewable && gridImages.length > 0 && (
          <>
            <div className={styles.actionsContainer}>
              <button
                className={styles.approveButton}
                onClick={handleApprove}
                disabled={isSubmitting}
              >
                审核通过
              </button>
              <button
                className={styles.rejectButton}
                onClick={() => setShowRejectInput(v => !v)}
                disabled={isSubmitting}
              >
                退回
              </button>
              <button
                className={styles.regenerateButton}
                onClick={handleRegenerate}
                disabled={isSubmitting}
              >
                {isRegenerating ? '重新生成中...' : '重新生成'}
              </button>
            </div>

            {showRejectInput && (
              <div className={styles.rejectInputContainer}>
                <textarea
                  className={styles.rejectInput}
                  placeholder="请输入退回原因..."
                  value={rejectReason}
                  onChange={e => setRejectReason(e.target.value)}
                  disabled={isSubmitting}
                  rows={2}
                />
                <div className={styles.rejectActions}>
                  <button
                    className={styles.rejectConfirmButton}
                    onClick={handleReject}
                    disabled={isSubmitting || !rejectReason.trim()}
                  >
                    确认退回
                  </button>
                  <button
                    className={styles.cancelButton}
                    onClick={() => { setShowRejectInput(false); setRejectReason(''); }}
                    disabled={isSubmitting}
                  >
                    取消
                  </button>
                </div>
              </div>
            )}
          </>
        )}

        {/* Failed state: only show regenerate */}
        {gridStatus === 'failed' && (
          <div className={styles.actionsContainer}>
            <button
              className={styles.regenerateButton}
              onClick={handleRegenerate}
              disabled={isSubmitting}
              style={{ flex: 1 }}
            >
              {isRegenerating ? '重新生成中...' : '重新生成'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
