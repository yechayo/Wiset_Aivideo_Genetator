import { ChevronDownIcon, ChevronRightIcon } from '../../../../components/icons/Icons';
import type { EpisodeState, SegmentPipelineStep } from '../types';
import styles from './EpisodeCard.module.less';
import { SegmentCard } from './SegmentCard';

interface EpisodeCardProps {
  chapterIndex: number;
  episode: EpisodeState;
  isExpanded: boolean;
  onToggle: () => void;
  expandedSegmentKey: string | null;
  onSegmentToggle: (key: string | null) => void;
  onSegmentApproveGrid: (episodeId: number, segmentIndex: number) => void;
  onSegmentRejectGrid: (episodeId: number, segmentIndex: number, reason: string) => void;
  onSegmentRegenerateGrid: (episodeId: number, segmentIndex: number) => void;
  onSegmentGenerateVideo: (episodeId: number, segmentIndex: number, customPrompt?: string) => void;
  generatingGridPanelId?: string | null;
  generatingVideoPanelId?: string | null;
  onRefreshPanels?: (episodeId: number) => void;
  onApproveEpisodeGrid?: () => void;
  onRejectEpisodeGrid?: (reason: string) => void;
  onRegenerateEpisodeGrid?: () => void;
  onRefreshEpisodeGrid?: () => void;
  onSegmentLoadVideoPrompt?: (episodeId: number, segmentIndex: number) => Promise<string>;
}

/**
 * 计算剧集的整体状态
 * - 已完成: 所有 segments 都是 video_completed
 * - 进行中: 有 segment 处于 grid_review/grid_approved/video_generating
 * - 未开始: 所有 segments 都是 pending
 */
const getEpisodeStatus = (episode: EpisodeState): 'completed' | 'in-progress' | 'not-started' => {
  if (episode.gridStatus === 'approved' && episode.segments.length > 0) {
    const allCompleted = episode.segments.every(s => s.pipelineStep === 'video_completed');
    if (allCompleted) return 'completed';
    const hasInProgress = episode.segments.some(s =>
      s.pipelineStep === 'video_generating' || s.pipelineStep === 'grid_approved'
    );
    return hasInProgress ? 'in-progress' : 'not-started';
  }
  if (episode.gridStatus === 'generating' || episode.gridStatus === 'generated') return 'in-progress';
  return 'not-started';
};

/**
 * 获取片段的状态颜色
 * - 绿色: video_completed
 * - 黄色: grid_review/grid_approved/video_generating
 * - 灰色: pending
 */
const getSegmentStatusColor = (step: SegmentPipelineStep): string => {
  if (step === 'video_completed') return '#4ade80';
  if (step === 'grid_review' || step === 'grid_approved' || step === 'grid_generating' || step === 'video_generating') return '#fbbf24';
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
  onSegmentApproveGrid,
  onSegmentRejectGrid,
  onSegmentRegenerateGrid,
  onSegmentGenerateVideo,
  onSegmentLoadVideoPrompt,
  generatingGridPanelId,
  generatingVideoPanelId,
  onRefreshPanels,
  onApproveEpisodeGrid,
  onRejectEpisodeGrid,
  onRegenerateEpisodeGrid,
  onRefreshEpisodeGrid,
}: EpisodeCardProps) => {
  const episodeStatus = getEpisodeStatus(episode);

  const isGridApproved = episode.gridStatus === 'approved';
  const isGridPending = episode.gridStatus === 'pending' || episode.gridStatus === 'generating';
  const isGridReview = episode.gridStatus === 'generated';
  const isGridRejected = episode.gridStatus === 'rejected';
  const isGridFailed = episode.gridStatus === 'failed';

  const isScriptGenerating = episode.scriptStatus === 'generating';
  const isStoryboardGenerating = episode.storyboardStatus === 'generating';

  /** 渲染骨架屏分镜条目 */
  const renderSkeletonSegments = (count: number = 3) => (
    Array.from({ length: count }).map((_, i) => (
      <div key={`skeleton-${i}`} className={styles.skeletonSegment}>
        <div className={styles.skeletonBarTitle} />
        <div className={styles.skeletonBarDesc} />
      </div>
    ))
  );

  /** 渲染生成进度提示 */
  const renderGeneratingHint = () => {
    if (isScriptGenerating) {
      return (
        <div className={styles.episodeGenerating}>
          <span className={styles.miniSpinner} />
          <span>分集剧本生成中...</span>
        </div>
      );
    }
    if (isStoryboardGenerating) {
      return (
        <div className={styles.episodeGenerating}>
          <span className={styles.miniSpinner} />
          <span>分镜脚本生成中...</span>
        </div>
      );
    }
    if (episode.gridStatus === 'generating') {
      return (
        <div className={styles.episodeGenerating}>
          <span className={styles.miniSpinner} />
          <span>九宫格生成中...</span>
        </div>
      );
    }
    return null;
  };

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
        onApproveGrid={() => onSegmentApproveGrid(episode.episodeId, segment.segmentIndex)}
        onRejectGrid={(reason) => onSegmentRejectGrid(episode.episodeId, segment.segmentIndex, reason)}
        onRegenerateGrid={() => onSegmentRegenerateGrid(episode.episodeId, segment.segmentIndex)}
        onGenerateVideo={(customPrompt) => onSegmentGenerateVideo(episode.episodeId, segment.segmentIndex, customPrompt)}
        onLoadVideoPrompt={onSegmentLoadVideoPrompt ? () => onSegmentLoadVideoPrompt(episode.episodeId, segment.segmentIndex) : undefined}
        isRegeneratingGrid={generatingGridPanelId === segment.panelData?.panelId}
        isGeneratingVideo={generatingVideoPanelId === segment.panelData?.panelId}
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
            <span className={styles.segmentCount}>
            {episode.gridStatus === 'approved' ? '九宫格已通过' :
             episode.gridStatus === 'generating' ? '九宫格生成中...' :
             episode.gridStatus === 'generated' ? '待审核九宫格' :
             episode.gridStatus === 'rejected' ? '九宫格已退回' :
             episode.gridStatus === 'failed' ? '九宫格生成失败' :
             '未生成九宫格'}
            {episode.gridStatus === 'approved' && episode.segments.length > 0 &&
              ` · ${episode.segments.length} 个片段`}
          </span>
          </div>

          {/* 九宫格审核按钮 */}
          {isExpanded && isGridReview && (
            <>
              <button
                className={styles.generatePanelsBtn}
                onClick={(e) => { e.stopPropagation(); onApproveEpisodeGrid?.(); }}
              >
                通过九宫格
              </button>
              <button
                className={styles.generatePanelsBtn}
                onClick={(e) => {
                  e.stopPropagation();
                  const reason = prompt('请输入拒绝原因：');
                  if (reason?.trim()) onRejectEpisodeGrid?.(reason.trim());
                }}
              >
                退回
              </button>
              <button
                className={styles.generatePanelsBtn}
                onClick={(e) => { e.stopPropagation(); onRegenerateEpisodeGrid?.(); }}
              >
                重新生成
              </button>
            </>
          )}

          {/* 九宫格被拒绝或生成失败时显示重新生成 */}
          {isExpanded && (isGridRejected || isGridFailed) && (
            <button
              className={styles.generatePanelsBtn}
              onClick={(e) => { e.stopPropagation(); onRegenerateEpisodeGrid?.(); }}
            >
              重新生成九宫格
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
          {!isExpanded && (
            <div className={styles.segmentIndicator}>
              <div
                className={styles.segmentDot}
                style={{
                  backgroundColor:
                    episode.gridStatus === 'approved' ? '#4ade80' :
                    episode.gridStatus === 'generated' ? '#fbbf24' :
                    episode.gridStatus === 'generating' ? '#fbbf24' :
                    '#474747',
                }}
                title={`九宫格: ${episode.gridStatus || 'pending'}`}
              />
              {episode.gridStatus === 'approved' && episode.segments.map((segment) => (
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
          {renderGeneratingHint()}

          {isScriptGenerating ? (
            renderSkeletonSegments(3)
          ) : isStoryboardGenerating ? (
            renderSkeletonSegments(5)
          ) : (
            <>
          {/* 九宫格审核阶段 */}
          {!isGridApproved && (
            <div className={styles.episodeGridReview}>
              {episode.gridImages && episode.gridImages.length > 0 ? (
                <div className={styles.gridImageList}>
                  {episode.gridImages.map((url, idx) => (
                    <div key={idx} className={styles.gridImagePage}>
                      <div className={styles.gridPageLabel}>第 {idx + 1} 页</div>
                      <img src={url} alt={`九宫格第${idx + 1}页`} className={styles.gridImage} />
                    </div>
                  ))}
                </div>
              ) : (
                <div className={styles.emptyState}>
                  <p>{isGridPending || episode.gridStatus === 'generating' ? '九宫格正在生成中，请稍候...' : '暂无九宫格'}</p>
                  {(isGridPending || episode.gridStatus === 'generating') && onRefreshEpisodeGrid && (
                    <button className={styles.refreshPanelsBtn} onClick={(e) => { e.stopPropagation(); onRefreshEpisodeGrid(); }}>
                      刷新
                    </button>
                  )}
                </div>
              )}

              {/* 分割后的分镜列表 */}
              {episode.splitShots && episode.splitShots.length > 0 && (
                <div className={styles.splitShotList}>
                  <h5 className={styles.splitShotTitle}>分镜列表（共 {episode.splitShots.length} 个）</h5>
                  <div className={styles.splitShotGrid}>
                    {episode.splitShots.map((shot, idx) => (
                      <div key={idx} className={styles.splitShotItem}>
                        <span className={styles.splitShotNumber}>#{shot.shotNumber}</span>
                        <span className={styles.splitShotDesc}>{shot.visualDescription}</span>
                        <span className={styles.splitShotDuration}>{shot.duration}s</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {episode.gridRejectionFeedback && (
                <div className={styles.rejectionFeedback}>
                  退回原因：{episode.gridRejectionFeedback}
                </div>
              )}
            </div>
          )}

          {/* 九宫格审核通过后显示 Panel 列表 */}
          {isGridApproved ? (
            episode.segments.length === 0 ? (
              <div className={styles.emptyState}>
                <p>暂无片段</p>
              </div>
            ) : (
              <div className={styles.segmentList}>
                {segmentCards}
              </div>
            )
          ) : null}
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default EpisodeCard;
