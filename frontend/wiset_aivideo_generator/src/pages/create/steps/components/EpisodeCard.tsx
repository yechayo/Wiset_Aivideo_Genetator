import { ChevronDownIcon, ChevronRightIcon } from '../../../../components/icons/Icons';
import type { EpisodeState, SegmentState, SegmentPipelineStep } from '../types';
import styles from './EpisodeCard.module.less';
import { SegmentCard } from './SegmentCard';

interface EpisodeCardProps {
  chapterIndex: number;
  episode: EpisodeState;
  isExpanded: boolean;
  onToggle: () => void;
  expandedSegmentKey: string | null;
  onSegmentToggle: (key: string | null) => void;
  onSegmentApprove: (episodeId: number, segmentIndex: number) => void;
  onSegmentRegenerate: (episodeId: number, segmentIndex: number, feedback: string) => void;
  onSegmentGenerateVideo: (episodeId: number, segmentIndex: number) => void;
  onSegmentGenerateComic?: (episodeId: number, segmentIndex: number) => void;
  generatingComicPanelId?: string | null;
  generatingVideoPanelId?: string | null;
  onSegmentReviseSingle?: (episodeId: number, segmentIndex: number, feedback: string) => void;
  isRevisingSinglePanelId?: string | null;
  onSegmentUpdatePanel?: (episodeId: number, segmentIndex: number, fields: Record<string, any>) => void;
  isUpdatingSinglePanelId?: string | null;
  onGeneratePanels?: (episodeId: number) => void;
  isGeneratingPanels?: boolean;
  onRefreshPanels?: (episodeId: number) => void;
  onGenerateBackground?: (episodeId: number, panelId: string) => void;
  generatingBackgroundPanelId?: string | null;
  onRevisePanel?: (episodeId: number) => void;
  isRevisingPanel?: boolean;
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
  onSegmentApprove,
  onSegmentRegenerate,
  onSegmentGenerateVideo,
  onSegmentGenerateComic,
  generatingComicPanelId,
  generatingVideoPanelId,
  onSegmentReviseSingle,
  isRevisingSinglePanelId,
  onSegmentUpdatePanel,
  isUpdatingSinglePanelId,
  onGeneratePanels,
  isGeneratingPanels,
  onRefreshPanels,
  onGenerateBackground,
  generatingBackgroundPanelId,
  onRevisePanel,
  isRevisingPanel,
}: EpisodeCardProps) => {
  const episodeStatus = getEpisodeStatus(episode.segments);

  // 只要有一个 panel 进入了生产流程（非 pending），就禁止修改分镜脚本
  const hasProductionStarted = episode.segments.some(s => s.pipelineStep !== 'pending');

  // Render segment cards using SegmentCard component
  const segmentCards = episode.segments.map((segment) => {
    const segmentKey = `${episode.episodeId}-${segment.segmentIndex}`;
    const isSegmentExpanded = expandedSegmentKey === segmentKey;

    return (
      <SegmentCard
        key={segment.segmentIndex}
        episodeId={episode.episodeId}
        segment={segment}
        sceneSummary={episode.sceneSummaryMap?.[segment.panelData?.planPanelId || '']}
        isExpanded={isSegmentExpanded}
        onToggle={() => onSegmentToggle(isSegmentExpanded ? null : segmentKey)}
        onApprove={() => onSegmentApprove(episode.episodeId, segment.segmentIndex)}
        onRegenerate={(feedback) => onSegmentRegenerate(episode.episodeId, segment.segmentIndex, feedback)}
        onGenerateVideo={() => onSegmentGenerateVideo(episode.episodeId, segment.segmentIndex)}
        onGenerateComic={onSegmentGenerateComic ? () => onSegmentGenerateComic(episode.episodeId, segment.segmentIndex) : undefined}
        isGeneratingComic={generatingComicPanelId === segment.panelData?.panelId}
        isGeneratingVideo={generatingVideoPanelId === segment.panelData?.panelId}
        onReviseSinglePanel={onSegmentReviseSingle ? (feedback) => onSegmentReviseSingle(episode.episodeId, segment.segmentIndex, feedback) : undefined}
        isRevisingPanel={isRevisingSinglePanelId === segment.panelData?.panelId}
        onUpdatePanel={onSegmentUpdatePanel ? (fields) => onSegmentUpdatePanel(episode.episodeId, segment.segmentIndex, fields) : undefined}
        isUpdatingPanel={isUpdatingSinglePanelId === segment.panelData?.panelId}
        onGenerateBackground={onGenerateBackground ? (panelId) => onGenerateBackground(episode.episodeId, panelId) : undefined}
        isGeneratingBackground={generatingBackgroundPanelId === segment.panelData?.panelId}
      />
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

          {/* 生成分镜按钮 */}
          {onGeneratePanels && (
            <button
              className={styles.generatePanelsBtn}
              onClick={(e) => { e.stopPropagation(); onGeneratePanels(episode.episodeId); }}
              disabled={isGeneratingPanels || hasProductionStarted}
              title={hasProductionStarted ? '已有分镜进入生产流程，无法重新生成脚本' : ''}
            >
              {isGeneratingPanels ? <><span className={styles.miniSpinner} />生成中...</> : '生成分镜脚本'}
            </button>
          )}

          {/* 修改分镜脚本按钮 */}
          {onRevisePanel && (
            <button
              className={styles.generatePanelsBtn}
              onClick={(e) => { e.stopPropagation(); onRevisePanel(episode.episodeId); }}
              disabled={isRevisingPanel || hasProductionStarted}
              title={hasProductionStarted ? '已有分镜进入生产流程，无法修改脚本' : ''}
            >
              {isRevisingPanel ? <><span className={styles.miniSpinner} />修改中...</> : '修改分镜脚本'}
            </button>
          )}

          {/* 刷新分镜按钮 */}
          {onRefreshPanels && isExpanded && (
            <button
              className={styles.refreshPanelsBtn}
              onClick={(e) => { e.stopPropagation(); onRefreshPanels(episode.episodeId); }}
              aria-label="刷新分镜"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" width="14" height="14">
                <path d="M1 4v6h6" />
                <path d="M23 20v-6h-6" />
                <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15" />
              </svg>
            </button>
          )}
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
              {segmentCards}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default EpisodeCard;
