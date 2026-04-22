import React from 'react';
import styles from './ShotDetail.module.less';
import type { StoryboardShot } from '../../../../services/types/episode.types';

export interface ShotDetailProps {
  shot: StoryboardShot;
  shotIndex: number;
  splitImageUrl?: string | null;
  onClose: () => void;
}

const FieldRow: React.FC<{ label: string; value: string }> = ({ label, value }) => {
  if (!value) return null;
  return (
    <div className={styles.fieldRow}>
      <span className={styles.fieldLabel}>{label}</span>
      <span className={styles.fieldValue}>{value}</span>
    </div>
  );
};

export const ShotDetail: React.FC<ShotDetailProps> = ({
  shot,
  splitImageUrl,
  onClose,
}) => {
  return (
    <div className={styles.shotDetail}>
      <div className={styles.header}>
        <h4 className={styles.title}>镜头 #{shot.shotNumber}</h4>
        <button className={styles.closeButton} onClick={onClose}>
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
            <path d="M4 4L12 12M12 4L4 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
        </button>
      </div>

      {/* Split image if available */}
      {splitImageUrl && (
        <div className={styles.splitImageContainer}>
          <img src={splitImageUrl} alt={`镜头 #${shot.shotNumber}`} className={styles.splitImage} />
        </div>
      )}

      <div className={styles.fields}>
        <FieldRow label="场景" value={shot.scene} />
        <FieldRow label="角色" value={shot.characters?.join('、')} />
        <FieldRow label="景别" value={shot.shotSize} />
        <FieldRow label="镜头角度" value={shot.cameraAngle} />
        <FieldRow label="运镜描述" value={shot.cameraMovement} />
        <FieldRow label="分镜描述" value={shot.sceneDescription} />
        <FieldRow label="台词" value={shot.dialogue} />
        {shot.dialogueTone && shot.dialogueTone !== '无' && (
          <FieldRow label="对白语气" value={shot.dialogueTone} />
        )}
        <FieldRow label="视觉效果" value={shot.visualEffects} />
        <FieldRow label="音效" value={shot.audioEffects} />
        {shot.transitionHint && shot.transitionHint !== '无' && !shot.transitionHint.includes('最后一个镜头') && (
          <FieldRow label="镜头衔接" value={shot.transitionHint} />
        )}
        <FieldRow label="时长" value={`${shot.duration}s`} />
        {shot.startTime !== undefined && (
          <FieldRow label="开始时间" value={`${shot.startTime}s`} />
        )}
        {shot.endTime !== undefined && (
          <FieldRow label="结束时间" value={`${shot.endTime}s`} />
        )}
      </div>
    </div>
  );
};
