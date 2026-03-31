import React from 'react';
import styles from './ShotTimeline.module.less';
import type { StoryboardShot } from '../../../../services/types/episode.types';

export interface ShotTimelineProps {
  shots: StoryboardShot[];
  selectedShotIndex: number | null;
  onSelectShot: (index: number) => void;
}

const ShotSizeIcon: React.FC<{ size: string }> = ({ size }) => {
  const sizeMap: Record<string, { icon: string; label: string }> = {
    WIDE_SHOT: { icon: 'W', label: '远景' },
    MID_SHOT: { icon: 'M', label: '中景' },
    CLOSE_UP: { icon: 'C', label: '特写' },
    OVER_SHOULDER: { icon: 'O', label: '过肩' },
  };
  const info = sizeMap[size] || { icon: '?', label: size };
  return (
    <span className={styles.shotSizeIcon} title={info.label}>
      {info.icon}
    </span>
  );
};

export const ShotTimeline: React.FC<ShotTimelineProps> = ({
  shots,
  selectedShotIndex,
  onSelectShot,
}) => {
  if (shots.length === 0) {
    return (
      <div className={styles.shotTimeline}>
        <span className={styles.empty}>暂无镜头数据</span>
      </div>
    );
  }

  const maxDuration = Math.max(...shots.map(s => s.duration));

  return (
    <div className={styles.shotTimeline}>
      <div className={styles.timelineTrack}>
        {shots.map((shot, idx) => {
          const widthPercent = maxDuration > 0 ? Math.max(10, (shot.duration / maxDuration) * 100) : 50;
          const isSelected = selectedShotIndex === idx;

          return (
            <div
              key={idx}
              className={`${styles.shotBlock} ${isSelected ? styles.selected : ''}`}
              style={{ width: `${widthPercent}px`, minWidth: '40px' }}
              onClick={() => onSelectShot(idx)}
              title={`镜头 #${shot.shotNumber}: ${shot.scene}`}
            >
              <span className={styles.shotNumber}>#{shot.shotNumber}</span>
              <div className={styles.shotBar}>
                <ShotSizeIcon size={shot.shotSize} />
                <span className={styles.shotDurationLabel}>{shot.duration}s</span>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
