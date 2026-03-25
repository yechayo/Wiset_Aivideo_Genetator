import { ChevronDownIcon, ChevronRightIcon } from '../../../../components/icons/Icons';
import type { EpisodeState, SegmentState, SegmentPipelineStep } from '../types';
import styles from './EpisodeCard.module.less';

interface EpisodeCardProps {
  chapterIndex: number;
  episode: EpisodeState;
  isExpanded: boolean;
  onToggle: () => void;
  expandedSegmentKey: string | null;
  onSegmentToggle: (key: string | null) => void;
}

/**
 * 计算剧集的整体状态
 * - 已完成: 所有 segments 都是 video_completed
 * - 进行中: 有 segment 处于 comic_review/comic_approved/video_generating
 * - 未开始: 所有 segments 都是 pending/scene_ready
 */
const getEpisodeStatus = (segments: SegmentState[]): 'completed' | 'in-progress' | 'not-started' => {
  if (segments.length === 0) return 'not-started';

  const allCompleted = segments.every(s => s.pipelineStep === 'video_completed');
  if (allCompleted) return 'completed';

  const hasInProgress = segments.some(s =>
    s.pipelineStep === 'comic_review' ||
    s.pipelineStep === 'comic_approved' ||
    s.pipelineStep === 'video_generating'
  );
  if (hasInProgress) return 'in-progress';

  return 'not-started';
};

/**
 * 获取片段的状态颜色
 * - 绿色: video_completed
 * - 黄色: comic_review/comic_approved/video_generating
 * - 灰色: pending/scene_ready
 */
const getSegmentStatusColor = (step: SegmentPipelineStep): string => {
  if (step === 'video_completed') return '#4ade80';
  if (step === 'comic_review' || step === 'comic_approved' || step === 'video_generating') return '#fbbf24';
  return '#474747';
};

/**
 * 剧集卡片组件
 * 显示剧集标题、简介、完成状态，支持折叠/展开
 */
const EpisodeCard = ({
  chapterIndex: _chapterIndex, // Prefix with underscore to indicate intentionally unused
  episode,
  isExpanded,
  onToggle,
  expandedSegmentKey,
  onSegmentToggle,
}: EpisodeCardProps) => {
  const episodeStatus = getEpisodeStatus(episode.segments);

  // 生成片段占位符（Task 4 会替换为 SegmentCard）
  const segmentPlaceholders = episode.segments.map((segment) => {
    const segmentKey = `${episode.episodeId}-${segment.segmentIndex}`;
    const isSegmentExpanded = expandedSegmentKey === segmentKey;

    return (
      <div
        key={segment.segmentIndex}
        className={styles.segmentPlaceholder}
        onClick={() => onSegmentToggle(isSegmentExpanded ? null : segmentKey)}
      >
        <div className={styles.segmentHeader}>
          <span className={styles.segmentIndex}>片段 {segment.segmentIndex + 1}</span>
          <span className={styles.segmentTitle}>{segment.title}</span>
          <span className={styles.segmentStatus} style={{ backgroundColor: getSegmentStatusColor(segment.pipelineStep) }} />
        </div>
        {isSegmentExpanded && (
          <div className={styles.segmentContent}>
            <p className={styles.segmentSynopsis}>{segment.synopsis}</p>
            <p className={styles.segmentHint}>（SegmentCard 组件将在 Task 4 中实现）</p>
          </div>
        )}
      </div>
    );
  });

  return (
    <div className={`${styles.episodeCard} ${styles[episodeStatus]} ${isExpanded ? styles.expanded : ''}`}>
      {/* 卡片头部 */}
      <div className={styles.cardHeader} onClick={onToggle}>
        <div className={styles.headerLeft}>
          {isExpanded ? (
            <ChevronDownIcon className={styles.chevron} />
          ) : (
            <ChevronRightIcon className={styles.chevron} />
          )}
          <div className={styles.titleSection}>
            <h4 className={styles.title}>
              第{episode.episodeIndex}集：{episode.title}
            </h4>
            <span className={styles.segmentCount}>{episode.segments.length} 个片段</span>
          </div>
        </div>

        <div className={styles.headerRight}>
          {/* 状态图标 */}
          {episodeStatus === 'completed' && (
            <div className={`${styles.statusIcon} ${styles.completed}`}>
              <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M20 6L9 17L4 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            </div>
          )}
          {episodeStatus === 'in-progress' && (
            <div className={`${styles.statusIcon} ${styles.inProgress}`}>
              <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2"/>
                <path d="M12 6V12L16 14" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
              </svg>
            </div>
          )}
          {episodeStatus === 'not-started' && (
            <div className={`${styles.statusIcon} ${styles.notStarted}`}>
              <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2"/>
                <path d="M12 8V16M8 12H16" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
              </svg>
            </div>
          )}

          {/* 片段完成指示器（折叠时显示） */}
          {!isExpanded && episode.segments.length > 0 && (
            <div className={styles.segmentIndicator}>
              {episode.segments.map((segment) => (
                <div
                  key={segment.segmentIndex}
                  className={styles.segmentDot}
                  style={{ backgroundColor: getSegmentStatusColor(segment.pipelineStep) }}
                  title={`片段 ${segment.segmentIndex + 1}: ${segment.pipelineStep}`}
                />
              ))}
            </div>
          )}
        </div>
      </div>

      {/* 展开内容 */}
      {isExpanded && (
        <div className={styles.cardContent}>
          {episode.segments.length === 0 ? (
            <div className={styles.emptyState}>
              <p>暂无片段</p>
            </div>
          ) : (
            <div className={styles.segmentList}>
              {segmentPlaceholders}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default EpisodeCard;
