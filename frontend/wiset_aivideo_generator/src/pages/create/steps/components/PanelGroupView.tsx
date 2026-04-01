import React from 'react';
import styles from './PanelGroupView.module.less';

interface PanelGroupViewProps {
  fusionImageUrl: string | null;
  shots: any[];
  gridStatus: string;
  onApprove?: () => void;
  onReject?: (reason: string) => void;
  onRegenerate?: () => void;
  isRegenerating?: boolean;
}

const shotSizeMap: Record<string, string> = {
  WIDE_SHOT: '远景',
  MID_SHOT: '中景',
  CLOSE_UP: '特写',
  OVER_SHOULDER: '过肩',
  Establishing: '建立镜头',
};

export const PanelGroupView: React.FC<PanelGroupViewProps> = ({
  fusionImageUrl,
  shots,
  gridStatus,
  onApprove,
  onReject,
  onRegenerate,
  isRegenerating,
}) => {
  const isApproved = gridStatus === 'approved';
  const isGenerated = gridStatus === 'generated';
  const totalDuration = shots.reduce((s: number, sh: any) => s + (sh.duration || 0), 0);

  return (
    <div className={styles.panelGroupView}>
      <div className={styles.header}>
        <h3 className={styles.title}>
          {shots.length} 个分镜 · {totalDuration}s
        </h3>
        {isApproved && (
          <div className={styles.statusBadge} style={{ backgroundColor: '#4ade80' }}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <path d="M20 6L9 17l-5-5" />
            </svg>
            <span>已通过</span>
          </div>
        )}
      </div>

      {fusionImageUrl && (
        <div className={styles.fusionImageContainer}>
          <img src={fusionImageUrl} alt="融合参考图" className={styles.fusionImage} />
          <span className={styles.fusionLabel}>融合参考图</span>
        </div>
      )}

      {shots.length > 0 && (
        <div className={styles.shotList}>
          {shots.map((shot: any, idx: number) => (
            <div key={idx} className={styles.shotItem}>
              <span className={styles.shotNumber}>#{shot.shotNumber || idx + 1}</span>
              <span className={styles.shotDuration}>{shot.duration}s</span>
              {shot.shotSize && (
                <span className={styles.shotSize}>
                  {shotSizeMap[shot.shotSize] || shot.shotSize}
                </span>
              )}
              <span className={styles.shotDesc}>
                {shot.visualDescription || shot.scene || ''}
              </span>
            </div>
          ))}
        </div>
      )}

      {isGenerated && (
        <div className={styles.actionBar}>
          {onApprove && (
            <button
              className={styles.approveBtn}
              onClick={onApprove}
              disabled={isRegenerating}
            >
              ✓ 通过
            </button>
          )}
          {onReject && (
            <button
              className={styles.rejectBtn}
              onClick={() => {
                const reason = prompt('请输入退回原因（可选）：') || '';
                onReject(reason);
              }}
              disabled={isRegenerating}
            >
              ✗ 退回
            </button>
          )}
          {onRegenerate && (
            <button
              className={styles.regenerateBtn}
              onClick={() => onRegenerate()}
              disabled={isRegenerating}
            >
              ⟳ 重新生成
            </button>
          )}
        </div>
      )}
    </div>
  );
};
