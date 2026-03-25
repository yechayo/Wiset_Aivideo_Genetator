import { useState, useCallback, useEffect, useRef } from 'react';
import styles from './Step5page.module.less';
import type { StepContentProps } from '../types';
import type { Project } from '../../../services/types/project.types';
import type {
  ChapterState,
  EpisodeState,
  SegmentState,
  ExpansionState,
} from './types';
import { useCreateStore } from '../../../stores/createStore';

const POLL_INTERVAL = 3000;

interface Step5pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step5page: Video Production Workstation
 *
 * Displays a three-level expandable list (Chapter → Episode → Segment)
 * with a top stats bar showing completion status.
 *
 * This is a skeleton implementation - child components will be added in later tasks.
 */
const Step5page = ({ project }: Step5pageProps) => {
  const { syncStatus } = useCreateStore();
  const projectId = project.projectId;

  // Data state
  const [chapters, setChapters] = useState<ChapterState[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // UI state: which episode/segment is expanded (accordion mode)
  const [expansion, setExpansion] = useState<ExpansionState>({
    expandedEpisodeId: null,
    expandedSegmentKey: null,
  });

  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Calculate completion stats
  const totalSegments = chapters.reduce(
    (sum, ch) => sum + ch.episodes.reduce((s, ep) => s + ep.segments.length, 0),
    0
  );
  const completedSegments = chapters.reduce(
    (sum, ch) =>
      sum +
      ch.episodes.reduce(
        (s, ep) => s + ep.segments.filter(seg => seg.pipelineStep === 'video_completed').length,
        0
      ),
    0
  );

  /**
   * Load project data and build chapter state tree
   * TODO: Task 8 will integrate real API calls
   */
  const loadProjectData = useCallback(async () => {
    if (!projectId) return;

    setLoading(true);
    setError(null);

    try {
      // Placeholder: build mock chapter structure from project metadata
      // In Task 8, this will call real API endpoints
      const mockChapters: ChapterState[] = [];
      const episodesPerChapter = project.episodesPerChapter || 4;
      const totalEpisodes = project.totalEpisodes || 0;
      const chapterCount = Math.ceil(totalEpisodes / episodesPerChapter);

      for (let i = 0; i < chapterCount; i++) {
        const chapterIndex = i + 1;
        const startEp = i * episodesPerChapter;
        const endEp = Math.min(startEp + episodesPerChapter, totalEpisodes);
        const episodesInChapter: EpisodeState[] = [];

        for (let epIdx = startEp; epIdx < endEp; epIdx++) {
          episodesInChapter.push({
            episodeId: epIdx + 1,
            episodeIndex: epIdx + 1,
            title: `第${epIdx + 1}集`,
            segments: [
              {
                segmentIndex: 0,
                title: `片段 1`,
                synopsis: '片段简介占位',
                sceneThumbnail: null,
                characterAvatars: [],
                pipelineStep: 'pending',
                comicUrl: null,
                videoUrl: null,
                feedback: '',
              },
            ],
          });
        }

        mockChapters.push({
          chapterIndex,
          title: `第${chapterIndex}章`,
          episodes: episodesInChapter,
        });
      }

      setChapters(mockChapters);
    } catch (err: any) {
      console.error('Failed to load project data:', err);
      setError(err?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [projectId, project.episodesPerChapter, project.totalEpisodes]);

  /**
   * Toggle episode expansion (accordion mode)
   */
  const toggleEpisode = useCallback((episodeId: number) => {
    setExpansion(prev => ({
      expandedEpisodeId: prev.expandedEpisodeId === episodeId ? null : episodeId,
      expandedSegmentKey: null, // Close segment when switching episodes
    }));
  }, []);

  /**
   * Start polling for production updates
   */
  const startPolling = useCallback(() => {
    if (!projectId) return;

    stopPolling();
    loadProjectData();

    pollingRef.current = setInterval(() => {
      // Sync status via existing store
      if (projectId) {
        syncStatus(projectId);
      }
      // Reload data will be implemented in Task 8
    }, POLL_INTERVAL);
  }, [projectId, loadProjectData, syncStatus]);

  /**
   * Stop polling
   */
  const stopPolling = useCallback(() => {
    if (pollingRef.current !== null) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  // Initial load and polling setup
  useEffect(() => {
    startPolling();
    return () => stopPolling();
  }, [startPolling, stopPolling]);

  /**
   * Render a placeholder episode card (will be replaced by EpisodeCard component in Task 3)
   */
  const renderEpisodePlaceholder = (episode: EpisodeState) => {
    const isExpanded = expansion.expandedEpisodeId === episode.episodeId;

    return (
      <div key={episode.episodeId} className={styles.episodePlaceholder}>
        <div className={styles.episodeHeader} onClick={() => toggleEpisode(episode.episodeId)}>
          <span className={styles.episodeTitle}>{episode.title}</span>
          <span className={styles.episodeArrow}>{isExpanded ? '▼' : '▶'}</span>
        </div>
        {isExpanded && (
          <div className={styles.episodeContent}>
            <div className={styles.segmentsPlaceholder}>
              {episode.segments.map(segment => (
                <div key={segment.segmentIndex} className={styles.segmentPlaceholder}>
                  <span>{segment.title}</span>
                  <span className={styles.pipelineStatus}>{segment.pipelineStep}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    );
  };

  /**
   * Render completion dot for a segment
   */
  const renderCompletionDot = (step: SegmentState['pipelineStep']) => {
    if (step === 'video_completed') {
      return <span className={styles.dotCompleted} />;
    }
    if (step === 'video_generating' || step === 'comic_review' || step === 'comic_approved') {
      return <span className={styles.dotInProgress} />;
    }
    return <span className={styles.dotPending} />;
  };

  // Loading state
  if (loading) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.loadingState}>
          <div className={styles.spinner} />
          <p>加载中...</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.errorState}>
          <p>{error}</p>
          <button onClick={loadProjectData} className={styles.retryButton}>
            重试
          </button>
        </div>
      </div>
    );
  }

  // Empty state
  if (chapters.length === 0) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.emptyState}>
          <p>暂无章节数据</p>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.pageContainer}>
      {/* Header with title and stats */}
      <div className={styles.pageHeader}>
        <div className={styles.titleSection}>
          <h1 className={styles.pageTitle}>视频生产</h1>
          <p className={styles.pageSubtitle}>管理剧集、片段和视频生成流程</p>
        </div>
        <div className={styles.statsBar}>
          <div className={styles.statsInfo}>
            <span className={styles.completedCount}>{completedSegments}</span>
            <span className={styles.separator}>/</span>
            <span className={styles.totalCount}>{totalSegments}</span>
            <span className={styles.statsLabel}>片段已完成</span>
          </div>
          <button className={styles.generateAllButton} disabled>
            一键生成
          </button>
        </div>
      </div>

      {/* Chapter groups */}
      <div className={styles.chapterList}>
        {chapters.map(chapter => {
          // Count segments in this chapter
          const chapterSegments = chapter.episodes.reduce(
            (sum, ep) => sum + ep.segments.length,
            0
          );
          const chapterCompleted = chapter.episodes.reduce(
            (sum, ep) =>
              sum + ep.segments.filter(seg => seg.pipelineStep === 'video_completed').length,
            0
          );

          return (
            <div key={chapter.chapterIndex} className={styles.chapterGroup}>
              {/* Chapter header */}
              <div className={styles.chapterHeader}>
                <div className={styles.chapterTitleRow}>
                  <span className={styles.chapterIcon}>📖</span>
                  <h2 className={styles.chapterTitle}>
                    第{chapter.chapterIndex}章 {chapter.title}
                  </h2>
                  <div className={styles.chapterDivider} />
                  <div className={styles.chapterStats}>
                    {chapter.episodes.map(ep =>
                      ep.segments.map(seg => (
                        <span key={`${ep.episodeId}-${seg.segmentIndex}`} className={styles.statDot}>
                          {renderCompletionDot(seg.pipelineStep)}
                        </span>
                      ))
                    )}
                  </div>
                </div>
                <div className={styles.chapterProgress}>
                  <span className={styles.chapterProgressText}>
                    {chapterCompleted} / {chapterSegments}
                  </span>
                </div>
              </div>

              {/* Episode cards (placeholder for now) */}
              <div className={styles.episodeList}>
                {chapter.episodes.map(ep => renderEpisodePlaceholder(ep))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default Step5page;
