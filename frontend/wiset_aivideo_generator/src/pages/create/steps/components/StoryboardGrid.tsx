import React from 'react';
import styles from './StoryboardGrid.module.less';
import type { StoryboardShot } from '../../../../services/types/episode.types';

export interface StoryboardGridProps {
  gridImages: string[];
  shots: StoryboardShot[];
  gridStatus: string;
  currentPage: number;
  onPageChange: (page: number) => void;
  totalPages: number;
}

const ShotSizeIcon: React.FC<{ size: string }> = ({ size }) => {
  const sizeMap: Record<string, string> = {
    WIDE_SHOT: '远景',
    MID_SHOT: '中景',
    CLOSE_UP: '特写',
    OVER_SHOULDER: '过肩',
  };
  return <span className={styles.shotSize}>{sizeMap[size] || size}</span>;
};

export const StoryboardGrid: React.FC<StoryboardGridProps> = ({
  gridImages,
  shots,
  gridStatus,
  currentPage,
  onPageChange,
  totalPages,
}) => {
  const isGenerating = gridStatus === 'generating';
  const isFailed = gridStatus === 'failed';
  const isEmpty = gridImages.length === 0;

  return (
    <div className={styles.storyboardGrid}>
      {/* Grid display */}
      <div className={styles.gridContainer}>
        {isGenerating && (
          <div className={styles.overlay}>
            <div className={styles.spinner} />
            <span>九宫格生成中...</span>
          </div>
        )}

        {isFailed && (
          <div className={`${styles.overlay} ${styles.failed}`}>
            <span>生成失败，请重新生成</span>
          </div>
        )}

        {isEmpty && !isGenerating && !isFailed && (
          <div className={styles.overlay}>
            <span>暂无九宫格图片</span>
          </div>
        )}

        {!isEmpty && !isGenerating && (
          <img
            src={gridImages[currentPage - 1] || gridImages[0]}
            alt={`九宫格第 ${currentPage} 页`}
            className={styles.gridImage}
          />
        )}
      </div>

      {/* Shot info list */}
      {shots.length > 0 && (
        <div className={styles.shotList}>
          <div className={styles.shotListHeader}>镜头列表 ({shots.length})</div>
          {shots.map((shot, idx) => (
            <div key={idx} className={styles.shotItem}>
              <span className={styles.shotNumber}>#{shot.shotNumber}</span>
              <span className={styles.shotDuration}>{shot.duration}s</span>
              <ShotSizeIcon size={shot.shotSize} />
              <span className={styles.shotScene}>{(shot.scene || '').substring(0, 30)}{(shot.scene || '').length > 30 ? '...' : ''}</span>
            </div>
          ))}
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className={styles.pagination}>
          <button
            className={styles.pageButton}
            onClick={() => onPageChange(Math.max(1, currentPage - 1))}
            disabled={currentPage <= 1}
          >
            上一页
          </button>
          <span className={styles.pageInfo}>
            {currentPage} / {totalPages}
          </span>
          <button
            className={styles.pageButton}
            onClick={() => onPageChange(Math.min(totalPages, currentPage + 1))}
            disabled={currentPage >= totalPages}
          >
            下一页
          </button>
        </div>
      )}
    </div>
  );
};
