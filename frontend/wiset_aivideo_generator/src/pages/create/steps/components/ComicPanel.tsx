import React, { useState } from 'react';
import styles from './ComicPanel.module.less';
import type { SegmentPipelineStep } from '../types';

export interface ComicPanelProps {
  comicUrl: string | null;
  pipelineStep: SegmentPipelineStep;
  onApprove: () => void;
  onRegenerate: (feedback: string) => void;
  onGenerateComic?: () => void;
  isGeneratingComic?: boolean;
}

export const ComicPanel: React.FC<ComicPanelProps> = ({
  comicUrl,
  pipelineStep,
  onApprove,
  onRegenerate,
  onGenerateComic,
  isGeneratingComic,
}) => {
  const [feedback, setFeedback] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleRegenerate = async () => {
    if (isSubmitting) return;
    setIsSubmitting(true);
    try {
      await onRegenerate(feedback);
      setFeedback('');
    } finally {
      setIsSubmitting(false);
    }
  };

  const renderPlaceholder = (message: string) => (
    <div className={styles.placeholder}>
      <div className={styles.placeholderIcon}>
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
          <rect x="3" y="3" width="18" height="18" rx="2" />
          <path d="M3 9h18" />
          <path d="M9 21V9" />
        </svg>
      </div>
      <p className={styles.placeholderText}>{message}</p>
    </div>
  );

  const renderComicGrid = () => {
    if (!comicUrl) {
      // 如果是 comic_review 状态但没有 URL，表示生成失败
      if (pipelineStep === 'comic_review') {
        return renderPlaceholder('四宫格生成失败，请点击下方按钮重新生成');
      }
      return renderPlaceholder('暂无四宫格漫画');
    }

    return (
      <div className={styles.comicContainer}>
        <div className={styles.comicGrid}>
          <img src={comicUrl} alt="四宫格漫画" className={styles.comicImage} />
        </div>
      </div>
    );
  };

  const renderStatusBadge = () => {
    switch (pipelineStep) {
      case 'comic_approved':
      case 'video_generating':
      case 'video_completed':
        // 四宫格已完成审核，后续阶段显示为已完成
        return (
          <div className={styles.statusBadge} style={{ backgroundColor: '#4ade80' }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M20 6L9 17l-5-5" />
            </svg>
            <span>已完成</span>
          </div>
        );
      case 'comic_review':
        // 没有漫画 URL 表示生成失败
        if (!comicUrl) {
          return (
            <div className={styles.statusBadge} style={{ backgroundColor: '#f2777b' }}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                <circle cx="12" cy="12" r="10" />
                <path d="M15 9l-6 6M9 9l6 6" />
              </svg>
              <span>生成失败</span>
            </div>
          );
        }
        // 有漫画 URL 表示等待审核
        return (
          <div className={styles.statusBadge} style={{ backgroundColor: '#fbbf24' }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <circle cx="12" cy="12" r="10" fill="none" />
              <path d="M12 6v6l4 2" />
            </svg>
            <span>待审核</span>
          </div>
        );
      case 'video_failed':
        return (
          <div className={styles.statusBadge} style={{ backgroundColor: '#f2777b' }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <circle cx="12" cy="12" r="10" />
              <path d="M15 9l-6 6M9 9l6 6" />
            </svg>
            <span>生成失败</span>
          </div>
        );
      default:
        return null;
    }
  };

  const renderActions = () => {
    if (pipelineStep !== 'comic_review') return null;

    // 生成失败状态：没有 comicUrl，只显示重新生成按钮
    if (!comicUrl) {
      return (
        <div className={styles.actionsContainer}>
          <button
            className={styles.regenerateButton}
            onClick={handleRegenerate}
            disabled={isSubmitting}
            style={{ flex: 1 }}
          >
            {isSubmitting ? '重新生成中...' : '重新生成'}
          </button>
        </div>
      );
    }

    // 正常审核状态：有 comicUrl，显示审核通过和重新生成按钮
    return (
      <div className={styles.actionsContainer}>
        <button
          className={styles.approveButton}
          onClick={onApprove}
          disabled={isSubmitting}
        >
          审核通过
        </button>
        <button
          className={styles.regenerateButton}
          onClick={handleRegenerate}
          disabled={isSubmitting}
        >
          {isSubmitting ? '重新生成中...' : '重新生成'}
        </button>
      </div>
    );
  };

  const renderFeedbackInput = () => {
    if (pipelineStep !== 'comic_review') return null;
    // 没有 comicUrl 时也显示输入框，方便用户输入失败原因
    const placeholder = !comicUrl ? '请输入失败原因或修改建议（可选）' : '请输入修改建议（可选）';

    return (
      <div className={styles.feedbackContainer}>
        <textarea
          className={styles.feedbackInput}
          placeholder={placeholder}
          value={feedback}
          onChange={(e) => setFeedback(e.target.value)}
          disabled={isSubmitting}
          rows={3}
        />
      </div>
    );
  };

  return (
    <div className={styles.comicPanel}>
      <div className={styles.header}>
        <h3 className={styles.title}>四宫格漫画</h3>
        {renderStatusBadge()}
      </div>

      <div className={styles.content}>
        {pipelineStep === 'pending' ? (
          renderPlaceholder('请先生成背景图')
        ) : pipelineStep === 'scene_ready' ? (
          <div className={styles.placeholder}>
            <div className={styles.placeholderIcon}>
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                <rect x="3" y="3" width="18" height="18" rx="2" />
                <path d="M3 9h18" />
                <path d="M9 21V9" />
              </svg>
            </div>
            <p className={styles.placeholderText}>背景图已就绪，可以生成四宫格漫画</p>
            {onGenerateComic && (
              <button
                className={styles.approveButton}
                onClick={onGenerateComic}
                disabled={isGeneratingComic}
                style={{ marginTop: 12 }}
              >
                {isGeneratingComic ? <><span className={styles.miniSpinner} />生成中...</> : '生成四宫格'}
              </button>
            )}
          </div>
        ) : (
          <>
            {renderComicGrid()}
            {renderFeedbackInput()}
            {renderActions()}
          </>
        )}
      </div>
    </div>
  );
};
