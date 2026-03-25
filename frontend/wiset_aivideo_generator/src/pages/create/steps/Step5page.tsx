import { useState, useCallback, useEffect, useRef } from 'react';
import styles from './Step5page.module.less';
import type { StepContentProps } from '../types';
import type { Project } from '../../../services/types/project.types';
import type {
  ChapterState,
  EpisodeState,
  SegmentState,
  ExpansionState,
  SegmentPipelineStep,
} from './types';
import { useCreateStore } from '../../../stores/createStore';
import { getScript } from '../../../services/projectService';
import { getPanelStates } from '../../../services/episodeService';
import type { ScriptContentResponse } from '../../../services/types/project.types';
import type { PanelState } from '../../../services/types/episode.types';
import EpisodeCard from './components/EpisodeCard';

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
   * Map panel states to segment pipeline step
   */
  const mapPanelsToPipelineStep = (panels: PanelState[]): SegmentPipelineStep => {
    if (panels.length === 0) return 'pending';

    const allFusionCompleted = panels.every(p => p.fusionStatus === 'completed');
    const allVideoCompleted = panels.every(p => p.videoStatus === 'completed');
    const anyVideoFailed = panels.some(p => p.videoStatus === 'failed');
    const anyVideoGenerating = panels.some(p => p.videoStatus === 'generating');
    const anyFusionUrlExists = panels.some(p => p.fusionUrl !== null);
    const anySceneExists = panels.some(p => p.sceneDescription !== null);

    if (allFusionCompleted && allVideoCompleted) return 'video_completed';
    if (anyVideoFailed) return 'video_failed';
    if (anyVideoGenerating) return 'video_generating';
    if (allFusionCompleted && !anyVideoGenerating) return 'comic_approved';
    if (anyFusionUrlExists) return 'comic_review';
    if (anySceneExists) return 'scene_ready';
    return 'pending';
  };

  /**
   * Load project data and build chapter state tree
   */
  const loadProjectData = useCallback(async () => {
    if (!projectId) return;

    setLoading(true);
    setError(null);

    try {
      // Step 1: Load script data to get episodes
      const scriptResponse = await getScript(projectId);
      if (scriptResponse.code !== 0 || !scriptResponse.data) {
        throw new Error(scriptResponse.message || 'Failed to load script');
      }

      const scriptData: ScriptContentResponse = scriptResponse.data;
      const episodes = scriptData.episodes || [];

      // Step 2: Group episodes by chapterTitle
      const chapterMap = new Map<string, typeof episodes>();
      episodes.forEach(ep => {
        const chapterTitle = ep.chapterTitle && ep.chapterTitle.trim() !== '' ? ep.chapterTitle : '未分章';
        if (!chapterMap.has(chapterTitle)) {
          chapterMap.set(chapterTitle, []);
        }
        chapterMap.get(chapterTitle)!.push(ep);
      });

      // Step 3: Build ChapterState array
      const builtChapters: ChapterState[] = [];
      let chapterIndex = 0;

      for (const [chapterTitle, chapterEpisodes] of chapterMap.entries()) {
        chapterIndex++;
        const episodeStates: EpisodeState[] = [];

        for (const ep of chapterEpisodes) {
          if (!ep.id) continue;

          const episodeId = ep.id;
          const episodeIndex = ep.episodeNum || chapterEpisodes.indexOf(ep) + 1;

          // Step 4: Get panel states for each episode
          let panels: PanelState[] = [];
          try {
            const panelsResponse = await getPanelStates(String(episodeId));
            if (panelsResponse.code === 0 && panelsResponse.data) {
              panels = panelsResponse.data;
            }
          } catch (err) {
            console.warn(`Failed to load panels for episode ${episodeId}:`, err);
          }

          // Step 5: Aggregate panels into segments (4 panels per segment)
          const segments: SegmentState[] = [];
          const PANELS_PER_SEGMENT = 4;

          for (let i = 0; i < panels.length; i += PANELS_PER_SEGMENT) {
            const segmentPanels = panels.slice(i, i + PANELS_PER_SEGMENT);
            const segmentIndex = Math.floor(i / PANELS_PER_SEGMENT);

            // Build segment state from panels
            const pipelineStep = mapPanelsToPipelineStep(segmentPanels);

            // Use first panel's data as representative
            const firstPanel = segmentPanels[0];
            const sceneThumbnail = firstPanel?.fusionUrl || null;

            // Build character avatars from dialogue (parse names from dialogue)
            const characterAvatars: { name: string; avatarUrl: string }[] = [];
            segmentPanels.forEach(panel => {
              if (panel.dialogue) {
                // Simple extraction: assume format "角色名: dialogue"
                const match = panel.dialogue.match(/^([^:：]+)[:：]/);
                if (match) {
                  const name = match[1].trim();
                  if (!characterAvatars.find(c => c.name === name)) {
                    characterAvatars.push({ name, avatarUrl: '' });
                  }
                }
              }
            });

            // Build comic URL from fusion URLs
            const comicUrl = segmentPanels.every(p => p.fusionUrl)
              ? segmentPanels.map(p => p.fusionUrl!).join(',')
              : null;

            // Build video URL
            const videoUrl = segmentPanels.every(p => p.videoUrl)
              ? segmentPanels.map(p => p.videoUrl!).join(',')
              : null;

            segments.push({
              segmentIndex,
              title: `片段 ${segmentIndex + 1}`,
              synopsis: firstPanel?.sceneDescription || `片段 ${segmentIndex + 1}`,
              sceneThumbnail,
              characterAvatars,
              pipelineStep,
              comicUrl,
              videoUrl,
              feedback: '',
            });
          }

          // If no segments, create a placeholder
          if (segments.length === 0) {
            segments.push({
              segmentIndex: 0,
              title: '片段 1',
              synopsis: ep.content || '暂无简介',
              sceneThumbnail: null,
              characterAvatars: [],
              pipelineStep: 'pending',
              comicUrl: null,
              videoUrl: null,
              feedback: '',
            });
          }

          episodeStates.push({
            episodeId,
            episodeIndex,
            title: ep.title,
            segments,
          });
        }

        builtChapters.push({
          chapterIndex,
          title: chapterTitle,
          episodes: episodeStates,
        });
      }

      setChapters(builtChapters);
    } catch (err: any) {
      console.error('Failed to load project data:', err);
      setError(err?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

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
   * Toggle segment expansion
   */
  const toggleSegment = useCallback((segmentKey: string | null) => {
    setExpansion(prev => ({
      ...prev,
      expandedSegmentKey: segmentKey,
    }));
  }, []);

  /**
   * Handle segment approve
   */
  const handleSegmentApprove = useCallback((episodeId: number, segmentIndex: number) => {
    setChapters(prevChapters =>
      prevChapters.map(chapter => ({
        ...chapter,
        episodes: chapter.episodes.map(ep => {
          if (ep.episodeId !== episodeId) return ep;
          return {
            ...ep,
            segments: ep.segments.map(seg => {
              if (seg.segmentIndex !== segmentIndex) return seg;
              return { ...seg, pipelineStep: 'comic_approved' as SegmentPipelineStep };
            }),
          };
        }),
      }))
    );
  }, []);

  /**
   * Handle segment regenerate
   */
  const handleSegmentRegenerate = useCallback((episodeId: number, segmentIndex: number, feedback: string) => {
    // For now, just log. In future, call API to regenerate
    console.log('Regenerate segment:', episodeId, segmentIndex, feedback);
    // TODO: Call API to regenerate scene/comic
  }, []);

  /**
   * Handle segment generate video
   */
  const handleSegmentGenerateVideo = useCallback((episodeId: number, segmentIndex: number) => {
    // For now, just update local state
    setChapters(prevChapters =>
      prevChapters.map(chapter => ({
        ...chapter,
        episodes: chapter.episodes.map(ep => {
          if (ep.episodeId !== episodeId) return ep;
          return {
            ...ep,
            segments: ep.segments.map(seg => {
              if (seg.segmentIndex !== segmentIndex) return seg;
              return { ...seg, pipelineStep: 'video_generating' as SegmentPipelineStep };
            }),
          };
        }),
      }))
    );
    // TODO: Call API to generate video
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
   * Render episode card using EpisodeCard component
   */
  const renderEpisodeCard = (chapterIndex: number, episode: EpisodeState) => {
    const isExpanded = expansion.expandedEpisodeId === episode.episodeId;

    return (
      <EpisodeCard
        key={episode.episodeId}
        chapterIndex={chapterIndex}
        episode={episode}
        isExpanded={isExpanded}
        onToggle={() => toggleEpisode(episode.episodeId)}
        expandedSegmentKey={expansion.expandedSegmentKey}
        onSegmentToggle={toggleSegment}
        onSegmentApprove={handleSegmentApprove}
        onSegmentRegenerate={handleSegmentRegenerate}
        onSegmentGenerateVideo={handleSegmentGenerateVideo}
      />
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

              {/* Episode cards */}
              <div className={styles.episodeList}>
                {chapter.episodes.map(ep => renderEpisodeCard(chapter.chapterIndex, ep))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default Step5page;
