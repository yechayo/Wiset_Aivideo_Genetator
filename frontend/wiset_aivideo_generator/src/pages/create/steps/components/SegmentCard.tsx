import React from 'react';
import type { SegmentState, SegmentPipelineStep } from '../types';
import styles from './SegmentCard.module.less';
import { ComicPanel } from './ComicPanel';
import VideoPanel from './VideoPanel';

export interface SegmentCardProps {
  episodeId: number;
  segment: SegmentState;
  isExpanded: boolean;
  onToggle: () => void;
  onApprove: () => void;
  onRegenerate: (feedback: string) => void;
  onGenerateVideo: () => void;
  onGenerateBackground?: (panelId: string) => void;
  isGeneratingBackground?: boolean;
}

/**
 * 获取流水线步骤状态
 */
const getPipelineStepStatus = (step: SegmentPipelineStep): {
  scene: 'completed' | 'active' | 'pending';
  comic: 'completed' | 'active' | 'pending';
  video: 'completed' | 'active' | 'pending';
} => {
  switch (step) {
    case 'pending':
      return { scene: 'pending', comic: 'pending', video: 'pending' };
    case 'scene_ready':
      return { scene: 'completed', comic: 'active', video: 'pending' };
    case 'comic_review':
      return { scene: 'completed', comic: 'active', video: 'pending' };
    case 'comic_approved':
      return { scene: 'completed', comic: 'completed', video: 'active' };
    case 'video_generating':
      return { scene: 'completed', comic: 'completed', video: 'active' };
    case 'video_completed':
      return { scene: 'completed', comic: 'completed', video: 'completed' };
    case 'video_failed':
      return { scene: 'completed', comic: 'completed', video: 'active' };
    default:
      return { scene: 'pending', comic: 'pending', video: 'pending' };
  }
};

/**
 * SegmentCard 组件
 * - 折叠状态：单行显示（状态图标 + 片段编号 + 摘要 + 场景 + 角色 + 进度指示器）
 * - 展开状态：Header + 左右分栏内容区（ComicPanel + VideoPanel）
 */
export const SegmentCard: React.FC<SegmentCardProps> = ({
  episodeId: _episodeId, // eslint-disable-line @typescript-eslint/no-unused-vars
  segment,
  isExpanded,
  onToggle,
  onApprove,
  onRegenerate,
  onGenerateVideo,
  onGenerateBackground,
  isGeneratingBackground,
}) => {
  const stepStatus = getPipelineStepStatus(segment.pipelineStep);

  return (
    <div className={`${styles.segmentCard} ${isExpanded ? styles.expanded : ''}`}>
      {/* Header - 折叠/展开都显示 */}
      <div className={styles.header} onClick={onToggle}>
        {/* 左侧：状态图标 + 片段编号 + 摘要 */}
        <div className={styles.headerLeft}>
          {/* 状态图标 */}
          <div className={styles.statusIcon}>
            {segment.pipelineStep === 'video_completed' && (
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <circle cx="8" cy="8" r="7" stroke="currentColor" strokeWidth="1.5" />
                <path
                  d="M5 8L7 10L11 6"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            )}
            {segment.pipelineStep === 'video_failed' && (
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <circle cx="8" cy="8" r="7" stroke="currentColor" strokeWidth="1.5" />
                <path
                  d="M5.5 5.5L10.5 10.5M10.5 5.5L5.5 10.5"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                />
              </svg>
            )}
            {segment.pipelineStep === 'video_generating' && (
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <circle cx="8" cy="8" r="7" stroke="currentColor" strokeWidth="1.5" strokeOpacity="0.3" />
                <path
                  d="M8 1V3M8 13V15M15 8H13M3 8H1M12.95 12.95L11.54 11.54M4.46 4.46L3.05 3.05M12.95 3.05L11.54 4.46M4.46 11.54L3.05 12.95"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                />
              </svg>
            )}
          </div>

          {/* 片段编号 */}
          <span className={styles.segmentIndex}>#{segment.segmentIndex + 1}</span>

          {/* 摘要 */}
          <span className={styles.synopsis}>{segment.synopsis}</span>
        </div>

        {/* 右侧：场景缩略图 + 角色头像 + 进度指示器 + 展开箭头 */}
        <div className={styles.headerRight}>
          {/* 场景缩略图 */}
          {segment.sceneThumbnail ? (
            <div className={styles.sceneThumbnail}>
              <img src={segment.sceneThumbnail} alt="" />
            </div>
          ) : (
            <div
              className={`${styles.sceneIcon} ${onGenerateBackground ? styles.clickable : ''}`}
              onClick={onGenerateBackground ? (e) => {
                e.stopPropagation();
                onGenerateBackground(segment.panelData?.panelId || '');
              } : undefined}
              title={onGenerateBackground ? '生成背景图' : undefined}
            >
              {isGeneratingBackground ? (
                <span className={styles.generatingText}>生成中...</span>
              ) : (
                <>
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                    <rect x="1" y="3" width="14" height="10" rx="2" stroke="currentColor" strokeWidth="1.5" />
                    <circle cx="5" cy="7" r="1.5" fill="currentColor" />
                    <path d="M2 12L4.5 9L7 11.5L10 8L14 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                  <span className={styles.scenePlaceholderText}>点击生成背景</span>
                  <span className={styles.clickIcon}>👆</span>
                </>
              )}
            </div>
          )}

          {/* 角色头像列表 */}
          {segment.characterAvatars.length > 0 && (
            <div className={styles.characterAvatars}>
              {segment.characterAvatars.slice(0, 3).map((char, idx) => (
                <div
                  key={idx}
                  className={styles.avatar}
                  style={{ zIndex: 10 - idx }}
                  title={char.name}
                >
                  {char.avatarUrl ? (
                    <img src={char.avatarUrl} alt={char.name} />
                  ) : (
                    char.name.charAt(0)
                  )}
                </div>
              ))}
              {segment.characterAvatars.length > 3 && (
                <div className={`${styles.avatar} ${styles.avatarMore}`}>
                  +{segment.characterAvatars.length - 3}
                </div>
              )}
            </div>
          )}

          {/* 三步进度指示器 */}
          <div className={styles.progressIndicator}>
            {/* 场景步骤 */}
            <div
              className={`${styles.stepDot} ${
                stepStatus.scene === 'completed'
                  ? styles.completed
                  : stepStatus.scene === 'active'
                  ? styles.active
                  : styles.pending
              }`}
              title="场景"
            >
              <svg width="10" height="10" viewBox="0 0 16 16" fill="none">
                <path
                  d="M8 1L10 6L15 6L11 10L12 15L8 12L4 15L5 10L1 6L6 6L8 1Z"
                  fill="currentColor"
                />
              </svg>
            </div>

            {/* 连接线 */}
            <div
              className={`${styles.stepLine} ${
                stepStatus.scene === 'completed' ? styles.completed : styles.pending
              }`}
            />

            {/* 四宫格步骤 */}
            <div
              className={`${styles.stepDot} ${
                stepStatus.comic === 'completed'
                  ? styles.completed
                  : stepStatus.comic === 'active'
                  ? styles.active
                  : styles.pending
              }`}
              title="四宫格"
            >
              <svg width="10" height="10" viewBox="0 0 16 16" fill="none">
                <rect x="1" y="1" width="6" height="6" rx="1" fill="currentColor" />
                <rect x="9" y="1" width="6" height="6" rx="1" fill="currentColor" />
                <rect x="1" y="9" width="6" height="6" rx="1" fill="currentColor" />
                <rect x="9" y="9" width="6" height="6" rx="1" fill="currentColor" />
              </svg>
            </div>

            {/* 连接线 */}
            <div
              className={`${styles.stepLine} ${
                stepStatus.comic === 'completed' ? styles.completed : styles.pending
              }`}
            />

            {/* 视频步骤 */}
            <div
              className={`${styles.stepDot} ${
                stepStatus.video === 'completed'
                  ? styles.completed
                  : stepStatus.video === 'active'
                  ? styles.active
                  : styles.pending
              }`}
              title="视频"
            >
              <svg width="10" height="10" viewBox="0 0 16 16" fill="none">
                <path
                  d="M2 4C2 3.44772 2.44772 3 3 3H13C13.5523 3 14 3.44772 14 4V12C14 12.5523 13.5523 13 13 13H3C2.44772 13 2 12.5523 2 12V4Z"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                />
                <path
                  d="M7 6.5V9.5L10 8L7 6.5Z"
                  fill="currentColor"
                />
              </svg>
            </div>
          </div>

          {/* 展开箭头 */}
          <div className={`${styles.expandArrow} ${isExpanded ? styles.expanded : ''}`}>
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <path
                d="M4 6L8 10L12 6"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </div>
        </div>
      </div>

      {/* 展开内容区 */}
      {isExpanded && (
        <div className={styles.content}>
          {/* 左侧：四宫格漫画面板 */}
          <div className={styles.panel}>
            <ComicPanel
              comicUrl={segment.comicUrl}
              pipelineStep={segment.pipelineStep}
              onApprove={onApprove}
              onRegenerate={onRegenerate}
            />
          </div>

          {/* 右侧：AI 视频面板 */}
          <div className={styles.panel}>
            <VideoPanel
              videoUrl={segment.videoUrl}
              pipelineStep={segment.pipelineStep}
              onGenerateVideo={onGenerateVideo}
            />
          </div>
        </div>
      )}
    </div>
  );
};
