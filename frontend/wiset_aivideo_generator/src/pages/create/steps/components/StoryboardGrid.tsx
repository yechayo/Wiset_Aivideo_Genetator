import React from 'react';
import styles from './StoryboardGrid.module.less';
import type { StoryboardShot } from '../../../../services/types/episode.types';

export interface StoryboardGridProps {
  gridImages: string[];
  /** 当前页的分镜（不是全部） */
  shots: StoryboardShot[];
  /** 当前页状态: pending | generating | generated | failed */
  currentPageStatus: string;
  /** 当前页是否在生成中（包含本地乐观状态） */
  isCurrentPageGenerating: boolean;
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
  currentPageStatus,
  isCurrentPageGenerating,
}) => {
  const isEmpty = gridImages.length === 0;
  const showSpinner = isCurrentPageGenerating;
  const showFailed = currentPageStatus === 'failed' && !isCurrentPageGenerating;

  return (
    <div className={styles.storyboardGrid}>
      {/* Grid display */}
      <div className={styles.gridContainer}>
        {showSpinner && (
          <div className={styles.overlay}>
            <div className={styles.spinner} />
            <span>宫格图生成中...</span>
          </div>
        )}

        {showFailed && (
          <div className={`${styles.overlay} ${styles.failed}`}>
            <span>生成失败，请重新生成</span>
          </div>
        )}

        {isEmpty && !showSpinner && !showFailed && (
          <div className={styles.overlay}>
            <span>暂无宫格图片</span>
          </div>
        )}

        {!isEmpty && !showSpinner && (
          <img
            src={gridImages[0]}
            alt="宫格图"
            className={styles.gridImage}
          />
        )}
      </div>

      {/* Shot info list (current page only) */}
      {shots.length > 0 && (
        <div className={styles.shotList}>
          <div className={styles.shotListHeader}>当前页镜头 ({shots.length})</div>
          {shots.map((shot, idx) => (
            <div key={idx} className={styles.shotItem}>
              <div className={styles.shotItemHeader}>
                <span className={styles.shotNumber}>#{shot.shotNumber}</span>
                <span className={styles.shotDuration}>{shot.duration}s</span>
                <ShotSizeIcon size={shot.shotSize} />
                <span className={styles.shotScene}>{shot.scene || ''}</span>
              </div>
              {(shot.visualDescription || shot.dialogue) && (
                <div className={styles.shotItemDetail}>
                  {shot.visualDescription && (
                    <div className={styles.shotDesc}>{shot.visualDescription}</div>
                  )}
                  {shot.dialogue && (
                    <div className={styles.shotDialogue}>
                      {typeof shot.dialogue === 'string'
                        ? shot.dialogue
                        : (shot.dialogue as any[]).map((d: any, i: number) =>
                            d.speaker ? `${d.speaker}：${d.text}` : d.text
                          ).join(' / ')}
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
