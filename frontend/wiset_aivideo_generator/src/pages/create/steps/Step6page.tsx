import { useEffect, useRef, useState, useCallback } from 'react';
import styles from './Step6page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';
import { useFusionStore } from '../../../stores/fusionStore';
import {
  getProductionPipeline,
  getVideoSegments,
  getPanelStates,
  generateSinglePanelVideo,
  autoContinue,
  retryProduction,
} from '../../../services/episodeService';
import type { ProductionPipelineResponse, VideoSegmentInfo, PanelState } from '../../../services/types/episode.types';
import PipelineView from './components/PipelineView';
import GridFusionEditor from './components/GridFusionEditor';
import PanelVideoCard from './components/PanelVideoCard';

interface Step6pageProps extends StepContentProps {
  project: Project;
}

type ViewMode = 'loading' | 'pipeline' | 'fusion' | 'atomic' | 'completed' | 'failed' | 'no-episode';

const Step6page = ({ project }: Step6pageProps) => {
  const { reset: resetFusion } = useFusionStore();
  const [viewMode, setViewMode] = useState<ViewMode>('loading');
  const [pipeline, setPipeline] = useState<ProductionPipelineResponse | null>(null);
  const [episodeId, setEpisodeId] = useState<string | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);
  const [videoSegments, setVideoSegments] = useState<VideoSegmentInfo[]>([]);
  const [segmentsExpanded, setSegmentsExpanded] = useState(false);
  const [segmentsLoading, setSegmentsLoading] = useState(false);

  // 原子化面板状态
  const [panelStates, setPanelStates] = useState<PanelState[]>([]);
  const [panelsLoading, setPanelsLoading] = useState(false);
  const [autoContinuing, setAutoContinuing] = useState(false);

  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const panelPollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const projectId = project.projectId;

  // 加载面板状态
  const loadPanelStates = useCallback(async (targetEpisodeId: string) => {
    try {
      setPanelsLoading(true);
      const res = await getPanelStates(targetEpisodeId);
      if (res.code === 200 && res.data) {
        setPanelStates(res.data);
      }
    } catch (e: any) {
      console.error('获取面板状态失败:', e);
    } finally {
      setPanelsLoading(false);
    }
  }, []);

  // 轮询面板状态（原子化模式下每5秒刷新）
  useEffect(() => {
    if (viewMode !== 'atomic' || !episodeId) return;

    loadPanelStates(episodeId);

    panelPollTimerRef.current = setInterval(() => {
      loadPanelStates(episodeId!);
    }, 5000);

    return () => {
      if (panelPollTimerRef.current) {
        clearInterval(panelPollTimerRef.current);
        panelPollTimerRef.current = null;
      }
    };
  }, [viewMode, episodeId, loadPanelStates]);

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

  // 轮询生产管线
  useEffect(() => {
    if (!projectId) return;

    const poll = async () => {
      try {
        const res = await getProductionPipeline(projectId);
        if (res.code === 200 && res.data) {
          setPipeline(res.data);
          setPollError(null);

          const currentEpisodeId = res.data.episodeId;
          if (currentEpisodeId) {
            setEpisodeId(currentEpisodeId);

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

          // 融合编辑中或原子化视图中不自动切换
          if (viewMode === 'fusion' || viewMode === 'atomic') {
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

          // 判断视图模式
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

          // 检查融合阶段是否已完成（grid_fusion 阶段 index=2）
          const fusionStage = stages.find((s) => s.key === 'grid_fusion');
          const fusionCompleted = fusionStage?.displayStatus === 'completed';

          if (!res.data.episodeId) {
            setViewMode('no-episode');
          } else if (allCompleted && res.data.finalVideoUrl) {
            setViewMode('completed');
          } else if (hasFailed) {
            setViewMode('failed');
          } else if (fusionCompleted && !hasWaitingUser) {
            // 融合已完成但整体未完成 → 自动进入原子化视图
            setViewMode('atomic');
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

  // 融合完成后进入原子化视图
  const handleFusionSubmitted = useCallback(() => {
    resetFusion();
    setViewMode('atomic');
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

  // 单格生成视频
  const handleGeneratePanelVideo = useCallback(async (panelIndex: number) => {
    if (!episodeId) return;
    try {
      await generateSinglePanelVideo(episodeId, panelIndex);
      await loadPanelStates(episodeId);
    } catch (e: any) {
      console.error('生成视频失败:', e);
    }
  }, [episodeId, loadPanelStates]);

  // 一键自动化
  const handleAutoContinue = useCallback(async () => {
    if (!episodeId) return;
    try {
      setAutoContinuing(true);
      await autoContinue(episodeId);
      setViewMode('pipeline');
    } catch (e: any) {
      console.error('一键自动化失败:', e);
    } finally {
      setAutoContinuing(false);
    }
  }, [episodeId]);

  const generatedSceneGrids = (pipeline?.sceneGridUrls ?? []).filter((url) => !!url);
  const generatedVideos = videoSegments.filter((seg) => !!seg.videoUrl);
  const completedPanels = panelStates.filter((p) => p.videoStatus === 'completed').length;
  const totalPanels = panelStates.length;
  const allPanelsCompleted = totalPanels > 0 && completedPanels === totalPanels;

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

      {/* 原子化视图：每个分镜格子独立展示和控制 */}
      {viewMode === 'atomic' && episodeId && (
        <div className={styles.atomicView}>
          {/* 顶部统计栏 */}
          <div className={styles.atomicStats}>
            <div className={styles.statsItem}>
              <span className={styles.statsLabel}>进度</span>
              <span className={styles.statsValue}>
                {completedPanels} / {totalPanels || '-'}
              </span>
            </div>
            <div className={styles.statsItem}>
              <span className={styles.statsLabel}>已完成</span>
              <span className={styles.statsValue}>{completedPanels}</span>
            </div>
            <div className={styles.statsItem}>
              <span className={styles.statsLabel}>等待生成</span>
              <span className={styles.statsValue}>
                {totalPanels - completedPanels}
              </span>
            </div>
          </div>

          {/* 面板网格 */}
          <div className={styles.atomicGrid}>
            {panelsLoading ? (
              <div className={styles.atomicLoading}>
                <div className={styles.spinner}></div>
                <p>正在加载面板状态...</p>
              </div>
            ) : panelStates.length > 0 ? (
              panelStates.map((panel) => (
                <PanelVideoCard
                  key={panel.panelIndex}
                  panel={panel}
                  onGenerateVideo={handleGeneratePanelVideo}
                />
              ))
            ) : (
              <div className={styles.atomicEmpty}>
                <p>暂无面板数据，请先完成融合</p>
              </div>
            )}
          </div>

          {/* 底部操作栏 */}
          <div className={styles.atomicActions}>
            <button
              className={styles.autoButton}
              onClick={handleAutoContinue}
              disabled={autoContinuing}
            >
              {autoContinuing ? '执行中...' : '一键自动化执行'}
            </button>
            {allPanelsCompleted && (
              <button
                className={styles.composeButton}
                onClick={handleAutoContinue}
              >
                统一合并生成最终视频
              </button>
            )}
          </div>
        </div>
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

      {pollError && viewMode !== 'fusion' && viewMode !== 'atomic' && (
        <div className={styles.errorState}>
          <p>状态轮询出错: {pollError}</p>
        </div>
      )}
    </div>
  );
};

export default Step6page;
