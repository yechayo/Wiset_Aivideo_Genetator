import { useEffect, useRef, useState, useCallback } from 'react';
import styles from './Step6page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';
import { useFusionStore } from '../../../stores/fusionStore';
import { getProductionPipeline, getVideoSegments, retryProduction } from '../../../services/episodeService';
import type { ProductionPipelineResponse, VideoSegmentInfo } from '../../../services/types/episode.types';
import PipelineView from './components/PipelineView';
import GridFusionEditor from './components/GridFusionEditor';

interface Step6pageProps extends StepContentProps {
  project: Project;
}

type ViewMode = 'loading' | 'pipeline' | 'fusion' | 'completed' | 'failed' | 'no-episode';

const Step6page = ({ project }: Step6pageProps) => {
  const { reset: resetFusion } = useFusionStore();
  const [viewMode, setViewMode] = useState<ViewMode>('loading');
  const [pipeline, setPipeline] = useState<ProductionPipelineResponse | null>(null);
  const [episodeId, setEpisodeId] = useState<string | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);
  const [videoSegments, setVideoSegments] = useState<VideoSegmentInfo[]>([]);
  const [segmentsExpanded, setSegmentsExpanded] = useState(false);
  const [segmentsLoading, setSegmentsLoading] = useState(false);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const projectId = project.projectId;

  const loadSegments = useCallback(async (targetEpisodeId: string, showLoading = true) => {
    try {
      if (showLoading) {
        setSegmentsLoading(true);
      }
      const res = await getVideoSegments(targetEpisodeId);
      if (res.code === 200 && res.data) {
        setVideoSegments(res.data);
      }
    } catch (e: any) {
      console.error('获取视频片段失败:', e);
    } finally {
      if (showLoading) {
        setSegmentsLoading(false);
      }
    }
  }, []);

  // Poll production pipeline
  useEffect(() => {
    if (!projectId) return;

    const poll = async () => {
      try {
        const res = await getProductionPipeline(projectId);
        if (res.code === 200 && res.data) {
          setPipeline(res.data);
          setPollError(null);

          // Update episodeId
          const currentEpisodeId = res.data.episodeId;
          if (currentEpisodeId) {
            setEpisodeId(currentEpisodeId);

            // 生产阶段实时拉取已完成片段，支持边生成边展示
            const hasVideoStageStarted = res.data.stages.some((s) =>
              ['video_generating', 'subtitle', 'composition', 'completed'].includes(s.key)
              && (s.displayStatus === 'active' || s.displayStatus === 'completed')
            );
            if (hasVideoStageStarted) {
              await loadSegments(currentEpisodeId, false);
            }
          } else {
            setEpisodeId(null);
            setVideoSegments([]);
          }

          // 融合编辑中不覆盖视图，仅在完成/失败时自动切换
          if (viewMode === 'fusion') {
            const hasFailed = res.data.stages.some(
              (s) => s.displayStatus === 'failed'
            );
            const allCompleted = res.data.stages.every(
              (s) => s.displayStatus === 'completed'
            );
            if (hasFailed) {
              setViewMode('failed');
              return;
            }
            if (allCompleted && res.data.finalVideoUrl) {
              setViewMode('completed');
            }
            return;
          }

          // Determine view mode
          const stages = res.data.stages;
          const hasWaitingUser = stages.some(
            (s) => s.displayStatus === 'waiting_user'
          );
          const hasFailed = stages.some(
            (s) => s.displayStatus === 'failed'
          );
          const allCompleted = stages.every(
            (s) => s.displayStatus === 'completed'
          );

          if (!res.data.episodeId) {
            setViewMode('no-episode');
          } else if (hasWaitingUser) {
            // Don't auto-switch to fusion, show pipeline with action button
            setViewMode('pipeline');
          } else if (allCompleted && res.data.finalVideoUrl) {
            setViewMode('completed');
          } else if (hasFailed) {
            setViewMode('failed');
          } else {
            setViewMode('pipeline');
          }
        }
      } catch (e: any) {
        console.error('轮询管线状态失败:', e);
        setPollError(e.message);
      }
    };

    poll();
    pollTimerRef.current = setInterval(poll, 3000);

    return () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
        pollTimerRef.current = null;
      }
    };
  }, [projectId, viewMode, loadSegments]);

  useEffect(() => {
    if (viewMode === 'completed' && episodeId) {
      loadSegments(episodeId);
    }
  }, [viewMode, episodeId, loadSegments]);

  const handleFusionClick = useCallback(() => {
    if (episodeId) {
      setViewMode('fusion');
    }
  }, [episodeId]);

  const handleFusionSubmitted = useCallback(() => {
    resetFusion();
    setViewMode('pipeline');
  }, [resetFusion]);

  const handleRetry = useCallback(async () => {
    if (!episodeId) return;
    try {
      await retryProduction(episodeId);
      setViewMode('pipeline');
    } catch (e: any) {
      console.error('重试失败:', e);
    }
  }, [episodeId]);

  const generatedSceneGrids = (pipeline?.sceneGridUrls ?? []).filter((url) => !!url);
  const generatedVideos = videoSegments.filter((seg) => !!seg.videoUrl);

  if (viewMode === 'loading') {
    return (
      <div className={styles.content}>
        <div className={styles.header}>
          <h1 className={styles.title}>生成进度</h1>
          <p className={styles.subtitle}>查看生成进度和下载</p>
        </div>
        <div className={styles.loadingState}>
          <div className={styles.spinner}></div>
          <p>正在加载生产状态...</p>
        </div>
      </div>
    );
  }

  if (viewMode === 'no-episode') {
    return (
      <div className={styles.content}>
        <div className={styles.header}>
          <h1 className={styles.title}>生成进度</h1>
          <p className={styles.subtitle}>查看生成进度和下载</p>
        </div>
        <div className={styles.emptyState}>
          <p>暂无可生成的剧集</p>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>生成进度</h1>
        <p className={styles.subtitle}>查看生成进度和下载</p>
      </div>

      {viewMode === 'pipeline' && pipeline && (
        <>
          <PipelineView
            stages={pipeline.stages}
            onFusionClick={handleFusionClick}
            onRetryClick={handleRetry}
          />

          {generatedSceneGrids.length > 0 && (
            <div className={styles.sceneGridSection}>
              <div className={styles.sceneGridTitle}>
                已生成场景图 ({generatedSceneGrids.length})
              </div>
              <div className={styles.sceneGridList}>
                {generatedSceneGrids.map((url, index) => (
                  <div key={`${url}-${index}`} className={styles.sceneGridItem}>
                    <img src={url} alt={`场景图${index + 1}`} />
                    <span className={styles.sceneGridLabel}>场景 {index + 1}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {generatedVideos.length > 0 && (
            <div className={styles.segmentsSection}>
              <button
                className={styles.segmentsToggle}
                onClick={() => setSegmentsExpanded(!segmentsExpanded)}
              >
                {segmentsExpanded ? '收起' : '展开'}已生成视频片段 ({generatedVideos.length} 个片段)
              </button>
              {segmentsExpanded && (
                <div className={styles.segmentsList}>
                  {generatedVideos.map((seg) => (
                    <div key={seg.panelIndex} className={styles.segmentItem}>
                      <div className={styles.segmentVideo}>
                        <video controls src={seg.videoUrl} preload="metadata" />
                      </div>
                      <div className={styles.segmentInfo}>
                        <span className={styles.segmentIndex}>#{seg.panelIndex + 1}</span>
                        <span className={styles.segmentDuration}>{seg.targetDuration}s</span>
                        <a
                          className={styles.segmentDownload}
                          href={seg.videoUrl}
                          download
                          target="_blank"
                          rel="noreferrer"
                        >
                          下载
                        </a>
                      </div>
                    </div>
                  ))}
                </div>
              )}
              {segmentsLoading && (
                <p className={styles.segmentsLoading}>正在加载视频片段...</p>
              )}
            </div>
          )}
        </>
      )}

      {viewMode === 'fusion' && episodeId && (
        <GridFusionEditor
          episodeId={episodeId}
          onFusionSubmitted={handleFusionSubmitted}
        />
      )}

      {viewMode === 'completed' && pipeline && (
        <div className={styles.completedView}>
          <div className={styles.completedIcon}>&#10003;</div>
          <p className={styles.completedMessage}>视频生产完成</p>
          <p className={styles.completedSub}>
            {pipeline.finalVideoUrl ? '可以下载最终视频' : '视频URL暂不可用'}
          </p>
          {pipeline.finalVideoUrl && (
            <>
              <div className={styles.videoContainer}>
                <video controls src={pipeline.finalVideoUrl} />
              </div>
              <a
                className={styles.downloadButton}
                href={pipeline.finalVideoUrl}
                download
                target="_blank"
                rel="noreferrer"
              >
                下载视频
              </a>
            </>
          )}

          {/* Video segments list */}
          {videoSegments.length > 0 && (
            <div className={styles.segmentsSection}>
              <button
                className={styles.segmentsToggle}
                onClick={() => setSegmentsExpanded(!segmentsExpanded)}
              >
                {segmentsExpanded ? '收起' : '展开'}视频片段列表 ({videoSegments.length} 个片段)
              </button>
              {segmentsExpanded && (
                <div className={styles.segmentsList}>
                  {videoSegments.map((seg) => (
                    <div key={seg.panelIndex} className={styles.segmentItem}>
                      <div className={styles.segmentVideo}>
                        <video controls src={seg.videoUrl} preload="metadata" />
                      </div>
                      <div className={styles.segmentInfo}>
                        <span className={styles.segmentIndex}>#{seg.panelIndex + 1}</span>
                        <span className={styles.segmentDuration}>{seg.targetDuration}s</span>
                        <a
                          className={styles.segmentDownload}
                          href={seg.videoUrl}
                          download
                          target="_blank"
                          rel="noreferrer"
                        >
                          下载
                        </a>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
          {segmentsLoading && (
            <p className={styles.segmentsLoading}>正在加载视频片段...</p>
          )}
        </div>
      )}

      {viewMode === 'failed' && pipeline && (
        <div className={styles.failedView}>
          <div className={styles.failedMessage}>
            {pipeline.errorMessage || '视频生产失败'}
          </div>
          <button className={styles.retryButton} onClick={handleRetry}>
            重试
          </button>
        </div>
      )}

      {pollError && viewMode !== 'fusion' && (
        <div className={styles.errorState}>
          <p>状态轮询出错: {pollError}</p>
        </div>
      )}
    </div>
  );
};

export default Step6page;
