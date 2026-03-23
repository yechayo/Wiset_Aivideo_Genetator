import { useEffect, useState, useCallback, useRef } from 'react';
import styles from './Step5page.module.less';
import type { StepContentProps } from '../types';
import type { Project, StoryboardData } from '../../../services';
import { useCreateStore } from '../../../stores/createStore';
import {
  getStoryboard,
  startStoryboard,
  confirmStoryboard,
  reviseStoryboard,
  retryStoryboard,
  startProduction,
} from '../../../services/projectService';
import {
  getProductionPipeline,
  getPanelStates,
  autoContinue,
  regenerateSceneImage,
  generateSinglePanelVideo,
} from '../../../services/episodeService';
import { isApiSuccess } from '../../../services/apiClient';
import type { WorkflowPhase, SceneImageState, ProductionPipelineResponse, PanelState } from '../../../services/types/episode.types';
import Step5Card from './components/Step5Card';
import GridFusionEditor from './components/GridFusionEditor';

const POLL_INTERVAL = 3000;

interface RawStoryboardCharacter {
  char_id?: string;
  name?: string;
  expression?: string;
  pose?: string;
  position?: string;
}

interface RawStoryboardDialogue {
  speaker?: string;
  text?: string;
}

interface RawStoryboardPanel {
  scene?: string;
  shot_size?: string;
  shot_type?: string;
  camera_angle?: string;
  dialogue?: string | RawStoryboardDialogue[];
  effects?: string;
  sfx?: string[];
  characters?: string | RawStoryboardCharacter[];
  background?: {
    scene_desc?: string;
  };
}

interface RawStoryboardData {
  panels?: RawStoryboardPanel[];
}

interface Step5pageProps extends StepContentProps {
  project: Project;
}

const formatCharacters = (characters: RawStoryboardPanel['characters']): string => {
  if (!characters) return '';
  if (typeof characters === 'string') return characters;
  if (!Array.isArray(characters)) return '';

  return characters
    .map((character) => {
      if (!character || typeof character !== 'object') return '';
      const label = character.name || character.char_id || '';
      const expression = character.expression ? ` ${character.expression}` : '';
      return `${label}${expression}`.trim();
    })
    .filter(Boolean)
    .join(' / ');
};

const formatDialogue = (dialogue: RawStoryboardPanel['dialogue']): string => {
  if (!dialogue) return '';
  if (typeof dialogue === 'string') return dialogue;
  if (!Array.isArray(dialogue)) return '';

  return dialogue
    .map((item) => {
      if (!item || typeof item !== 'object') return '';
      const speaker = item.speaker?.trim();
      const text = item.text?.trim();
      if (!speaker && !text) return '';
      return speaker ? `${speaker}: ${text || ''}`.trim() : (text || '');
    })
    .filter(Boolean)
    .join(' / ');
};

const normalizeStoryboardData = (raw: RawStoryboardData): StoryboardData => ({
  panels: Array.isArray(raw?.panels)
    ? raw.panels.map((panel) => ({
        scene: panel.scene || panel.background?.scene_desc || '',
        characters: formatCharacters(panel.characters),
        shot_size: panel.shot_size || panel.shot_type || '',
        camera_angle: panel.camera_angle || '',
        dialogue: formatDialogue(panel.dialogue),
        effects: panel.effects || (Array.isArray(panel.sfx) ? panel.sfx.join(' / ') : ''),
      }))
    : [],
});

const Step5page = ({ project }: Step5pageProps) => {
  const { statusInfo, syncStatus } = useCreateStore();
  const projectId = project.projectId;

  const currentEpisode = statusInfo?.storyboardCurrentEpisode ?? 0;
  const totalEpisodes = statusInfo?.storyboardTotalEpisodes ?? 0;
  const reviewEpisodeId = statusInfo?.storyboardReviewEpisodeId ?? null;
  const allConfirmed = statusInfo?.storyboardAllConfirmed ?? false;
  const isGenerating = statusInfo?.isGenerating ?? false;
  const isFailed = statusInfo?.isFailed ?? false;
  const statusDescription = statusInfo?.statusDescription ?? '';

  const [feedback, setFeedback] = useState('');
  const [showRevision, setShowRevision] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [apiError, setApiError] = useState<string | null>(null);
  const [storyboardData, setStoryboardData] = useState<StoryboardData | null>(null);

  // 工作流阶段状态
  const [workflowPhase, setWorkflowPhase] = useState<WorkflowPhase>('review');
  const [pipeline, setPipeline] = useState<ProductionPipelineResponse | null>(null);
  const [panelStates, setPanelStates] = useState<PanelState[]>([]);
  const [sceneImageStates, setSceneImageStates] = useState<Map<number, SceneImageState>>(new Map());
  const [autoContinuing, setAutoContinuing] = useState(false);

  // 手动融合模态框
  const [manualFusionPanelIndex, setManualFusionPanelIndex] = useState<number | null>(null);

  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const pipelinePollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const loadedEpisodeRef = useRef<string | null>(null);
  const syncedStoryboardEpisodeRef = useRef<string | null>(null);

  const fetchStoryboard = useCallback(async (epId: string) => {
    try {
      const res = await getStoryboard(epId);
      if (res.code !== 200 || !res.data) return false;

      if (res.data.status === 'STORYBOARD_GENERATING') return false;
      if (res.data.status === 'STORYBOARD_FAILED') return false;

      if (res.data.storyboardJson) {
        const parsed = normalizeStoryboardData(JSON.parse(res.data.storyboardJson) as RawStoryboardData);
        if (parsed?.panels?.length > 0) {
          setStoryboardData(parsed);
          return true;
        }
      }
    } catch {
      // ignore polling errors and retry on next interval
    }
    return false;
  }, []);

  const stopPolling = useCallback(() => {
    if (pollingRef.current !== null) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  const stopPipelinePolling = useCallback(() => {
    if (pipelinePollingRef.current !== null) {
      clearInterval(pipelinePollingRef.current);
      pipelinePollingRef.current = null;
    }
  }, []);

  const startPolling = useCallback((epId: string) => {
    stopPolling();
    loadedEpisodeRef.current = epId;

    fetchStoryboard(epId).then((hasData) => {
      if (hasData || loadedEpisodeRef.current !== epId) return;
      pollingRef.current = setInterval(async () => {
        if (loadedEpisodeRef.current !== epId) {
          stopPolling();
          return;
        }
        const done = await fetchStoryboard(epId);
        if (done) stopPolling();
      }, POLL_INTERVAL);
    });
  }, [fetchStoryboard, stopPolling]);

  // 加载面板状态
  const loadPanelStates = useCallback(async (epId: string) => {
    try {
      const res = await getPanelStates(epId);
      if (res.code === 200 && res.data) {
        setPanelStates(res.data);

        // 更新场景图状态
        const newSceneStates = new Map<number, SceneImageState>();
        const sceneGridUrls = pipeline?.sceneGridUrls ?? [];

        res.data.forEach((panel) => {
          const sceneUrl = sceneGridUrls[panel.panelIndex] || null;
          newSceneStates.set(panel.panelIndex, {
            url: sceneUrl,
            generating: !sceneUrl && panel.fusionStatus === 'pending',
            failed: false,
            prompt: panel.sceneDescription || null,
          });
        });

        setSceneImageStates(newSceneStates);
      }
    } catch (e) {
      console.error('获取面板状态失败:', e);
    }
  }, [pipeline]);

  // 加载管线状态
  const loadPipeline = useCallback(async (projId: string) => {
    try {
      const res = await getProductionPipeline(projId);
      if (res.code === 200 && res.data) {
        setPipeline(res.data);
        if (res.data.episodeId) {
          await loadPanelStates(res.data.episodeId);
        }
      }
    } catch (e) {
      console.error('获取管线状态失败:', e);
    }
  }, [loadPanelStates]);

  // 启动管线轮询
  const startPipelinePolling = useCallback((projId: string) => {
    stopPipelinePolling();
    loadPipeline(projId);
    pipelinePollingRef.current = setInterval(() => {
      loadPipeline(projId);
    }, POLL_INTERVAL);
  }, [loadPipeline, stopPipelinePolling]);

  useEffect(() => {
    setStoryboardData(null);
    setShowRevision(false);
    syncedStoryboardEpisodeRef.current = null;
    stopPipelinePolling();

    if (reviewEpisodeId) {
      startPolling(reviewEpisodeId);
    } else {
      stopPolling();
    }

    return () => stopPolling();
  }, [reviewEpisodeId, startPolling, stopPolling, stopPipelinePolling]);

  // 当所有分镜确认后，开始管线轮询
  useEffect(() => {
    if (!allConfirmed || !projectId) {
      stopPipelinePolling();
      return;
    }

    startPipelinePolling(projectId);

    return () => stopPipelinePolling();
  }, [allConfirmed, projectId, startPipelinePolling, stopPipelinePolling]);

  useEffect(() => {
    if (!apiError) return;
    const timer = setTimeout(() => setApiError(null), 5000);
    return () => clearTimeout(timer);
  }, [apiError]);

  useEffect(() => {
    if (!isGenerating || !storyboardData || !reviewEpisodeId || !projectId) return;
    if (syncedStoryboardEpisodeRef.current === reviewEpisodeId) return;

    syncedStoryboardEpisodeRef.current = reviewEpisodeId;
    void syncStatus(projectId);
  }, [isGenerating, storyboardData, reviewEpisodeId, projectId, syncStatus]);

  // 判断工作流阶段
  useEffect(() => {
    if (!allConfirmed) {
      setWorkflowPhase('review');
      return;
    }

    const stages = pipeline?.stages ?? [];
    const sceneGridUrls = pipeline?.sceneGridUrls ?? [];

    // 检查场景图是否全部生成
    const allSceneGenerated = sceneGridUrls.length > 0 && sceneGridUrls.every(url => !!url);

    if (!allSceneGenerated) {
      setWorkflowPhase('scene-generating');
      return;
    }

    // 检查融合状态
    const fusionStage = stages.find(s => s.key === 'grid_fusion');
    if (fusionStage?.displayStatus !== 'completed') {
      setWorkflowPhase('fusion');
      return;
    }

    // 检查视频状态
    const allPanelsCompleted = panelStates.every(p => p.videoStatus === 'completed');
    if (allPanelsCompleted && panelStates.length > 0) {
      setWorkflowPhase('completed');
    } else {
      setWorkflowPhase('video');
    }
  }, [allConfirmed, pipeline, panelStates]);

  // Helper: 从 panelStates 获取指定格子的融合状态
  const getFusionStatus = useCallback((panelIndex: number): 'pending' | 'completed' | 'failed' => {
    const panel = panelStates.find(p => p.panelIndex === panelIndex);
    return panel?.fusionStatus ?? 'pending';
  }, [panelStates]);

  // Helper: 从 panelStates 获取指定格子的视频状态
  const getVideoStatus = useCallback((panelIndex: number): 'pending' | 'generating' | 'completed' | 'failed' => {
    const panel = panelStates.find(p => p.panelIndex === panelIndex);
    return panel?.videoStatus ?? 'pending';
  }, [panelStates]);

  // Helper: 从 panelStates 获取指定格子的融合 URL
  const getFusionUrl = useCallback((panelIndex: number): string | null => {
    const panel = panelStates.find(p => p.panelIndex === panelIndex);
    return panel?.fusionUrl ?? null;
  }, [panelStates]);

  // Helper: 从 panelStates 获取指定格子的视频 URL
  const getVideoUrl = useCallback((panelIndex: number): string | null => {
    const panel = panelStates.find(p => p.panelIndex === panelIndex);
    return panel?.videoUrl ?? null;
  }, [panelStates]);

  const handleConfirm = useCallback(async () => {
    if (!reviewEpisodeId) return;
    setIsSubmitting(true);
    setApiError(null);
    stopPolling();
    setStoryboardData(null);
    try {
      const res = await confirmStoryboard(reviewEpisodeId);
      if (isApiSuccess(res)) {
        if (projectId) {
          await syncStatus(projectId);
        }
        return;
      }
      setApiError(res.message || 'Confirm failed');
    } catch (e: any) {
      setApiError(e.message || 'Confirm failed');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId, projectId, stopPolling, syncStatus]);

  const handleRetry = useCallback(async () => {
    setIsSubmitting(true);
    setApiError(null);
    stopPolling();
    setStoryboardData(null);
    try {
      if (!reviewEpisodeId) {
        if (!projectId) {
          setApiError('Missing project id. Please refresh and retry.');
          return;
        }
        const fallbackRes = await startStoryboard(projectId);
        if (!isApiSuccess(fallbackRes)) {
          setApiError(fallbackRes.message || 'Retry failed');
          return;
        }
        await syncStatus(projectId);
        return;
      }

      const res = await retryStoryboard(reviewEpisodeId);
      if (isApiSuccess(res)) {
        if (projectId) {
          await syncStatus(projectId);
        }
        startPolling(reviewEpisodeId);
        return;
      }
      setApiError(res.message || 'Retry failed');
    } catch (e: any) {
      setApiError(e.message || 'Retry failed');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId, projectId, stopPolling, startPolling, syncStatus]);

  const handleRefreshGeneratingState = useCallback(async () => {
    if (!projectId) return;

    setIsSubmitting(true);
    setApiError(null);
    try {
      if (reviewEpisodeId) {
        await fetchStoryboard(reviewEpisodeId);
      }
      await syncStatus(projectId);
    } catch (e: any) {
      setApiError(e.message || 'Failed to refresh status');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, reviewEpisodeId, fetchStoryboard, syncStatus]);

  const handleRevise = useCallback(async () => {
    if (!reviewEpisodeId || !feedback.trim()) return;
    setIsSubmitting(true);
    setApiError(null);
    stopPolling();
    setStoryboardData(null);
    try {
      const res = await reviseStoryboard(reviewEpisodeId, feedback.trim());
      if (isApiSuccess(res)) {
        setFeedback('');
        setShowRevision(false);
        if (projectId) {
          await syncStatus(projectId);
        }
        startPolling(reviewEpisodeId);
        return;
      }
      setApiError(res.message || 'Revision failed');
    } catch (e: any) {
      setApiError(e.message || 'Revision failed');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId, feedback, projectId, stopPolling, startPolling, syncStatus]);

  const handleStartProduction = useCallback(async () => {
    if (!projectId) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await startProduction(projectId);
      if (!isApiSuccess(res)) {
        setApiError(res.message || 'Failed to start production');
      }
    } catch (e: any) {
      setApiError(e.message || 'Failed to start production');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId]);

  // 重新生成场景图
  const handleRegenerateScene = useCallback(async (panelIndex: number) => {
    if (!reviewEpisodeId) return;
    try {
      await regenerateSceneImage(reviewEpisodeId, panelIndex);
      // 重新加载面板状态
      await loadPanelStates(reviewEpisodeId);
    } catch (e: any) {
      console.error('重新生成场景图失败:', e);
      setApiError(e.message || '重新生成场景图失败');
    }
  }, [reviewEpisodeId, loadPanelStates]);

  // 自动融合（单格）
  const handleAutoFusion = useCallback(async (_panelIndex: number) => {
    if (!reviewEpisodeId) return;
    try {
      await autoContinue(reviewEpisodeId);
      await loadPanelStates(reviewEpisodeId);
    } catch (e: any) {
      console.error('自动融合失败:', e);
      setApiError(e.message || '自动融合失败');
    }
  }, [reviewEpisodeId, loadPanelStates]);

  // 手动融合（打开模态框）
  const handleOpenManualFusion = useCallback((panelIndex: number) => {
    setManualFusionPanelIndex(panelIndex);
  }, []);

  // 关闭手动融合模态框
  const handleCloseManualFusion = useCallback(() => {
    setManualFusionPanelIndex(null);
  }, []);

  // 融合提交完成
  const handleFusionSubmitted = useCallback(async () => {
    setManualFusionPanelIndex(null);
    if (projectId) {
      await loadPipeline(projectId);
    }
  }, [projectId, loadPipeline]);

  // 生成视频（单格）
  const handleGenerateVideo = useCallback(async (panelIndex: number) => {
    if (!reviewEpisodeId) return;
    try {
      await generateSinglePanelVideo(reviewEpisodeId, panelIndex);
      await loadPanelStates(reviewEpisodeId);
    } catch (e: any) {
      console.error('生成视频失败:', e);
      setApiError(e.message || '生成视频失败');
    }
  }, [reviewEpisodeId, loadPanelStates]);

  // 一键自动化执行
  const handleAutoContinueAll = useCallback(async () => {
    if (!reviewEpisodeId) return;
    try {
      setAutoContinuing(true);
      await autoContinue(reviewEpisodeId);
    } catch (e: any) {
      console.error('一键自动化失败:', e);
      setApiError(e.message || '一键自动化失败');
    } finally {
      setAutoContinuing(false);
    }
  }, [reviewEpisodeId]);

  // 统一合并生成最终视频
  const handleCompose = useCallback(async () => {
    if (!reviewEpisodeId) return;
    try {
      await autoContinue(reviewEpisodeId);
    } catch (e: any) {
      console.error('合并失败:', e);
      setApiError(e.message || '合并失败');
    }
  }, [reviewEpisodeId]);

  const progressPercent = totalEpisodes > 0
    ? Math.round((allConfirmed ? 1 : Math.max(0, currentEpisode - 1) / totalEpisodes) * 100)
    : 0;

  // 底部操作栏可见性（阶段2-4始终可见）
  const showBottomBar = ['scene-generating', 'fusion', 'video'].includes(workflowPhase);

  // 阶段进度
  const phases = ['分镜审查', '场景生成', '图片融合', '视频生成', '完成'];
  const phaseIndex = {
    'review': 0,
    'scene-generating': 1,
    'fusion': 2,
    'video': 3,
    'completed': 4,
  }[workflowPhase] ?? 0;

  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>Storyboard Review</h1>
        <p className={styles.subtitle}>Review and confirm storyboard episode by episode.</p>
      </div>

      {totalEpisodes > 0 && (
        <div className={styles.progressBar}>
          <div className={styles.progressTrack}>
            <div className={styles.progressFill} style={{ width: `${progressPercent}%` }} />
          </div>
          <span className={styles.progressText}>
            {allConfirmed ? totalEpisodes : currentEpisode} / {totalEpisodes} episodes
          </span>
        </div>
      )}

      {/* 阶段进度条（仅在非 review 阶段显示） */}
      {workflowPhase !== 'review' && (
        <div className={styles.phaseProgressBar}>
          {phases.map((label, idx) => (
            <div
              key={label}
              className={`${styles.phaseStep} ${idx <= phaseIndex ? styles.phaseActive : ''} ${idx < phaseIndex ? styles.phaseDone : ''}`}
            >
              <div className={styles.phaseDot}>{idx < phaseIndex ? '✓' : idx + 1}</div>
              <span className={styles.phaseLabel}>{label}</span>
            </div>
          ))}
        </div>
      )}

      {isGenerating && (
        <div className={styles.generatingState}>
          <div className={styles.spinner} />
          <p>{statusDescription || 'Generating storyboard...'}</p>
          <button
            className={styles.retryButton}
            onClick={handleRefreshGeneratingState}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Refreshing...' : 'Refresh Status'}
          </button>
        </div>
      )}

      {isFailed && (
        <div className={styles.failedState}>
          <div className={styles.failedIcon}>!</div>
          <p className={styles.failedMessage}>{statusDescription || 'Storyboard generation failed'}</p>
          <button
            className={styles.retryButton}
            onClick={handleRetry}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Processing...' : 'Retry Generation'}
          </button>
        </div>
      )}

      {/* 阶段1：分镜审查（保持原有逻辑） */}
      {!isGenerating && !isFailed && !allConfirmed && totalEpisodes > 0 && currentEpisode <= totalEpisodes && (
        <div className={styles.reviewSection}>
          <div className={styles.reviewHeader}>
            <h2 className={styles.episodeTitle}>Episode {currentEpisode}</h2>
            <span className={styles.episodeBadge}>
              {currentEpisode === totalEpisodes ? 'Last Episode' : `${totalEpisodes - currentEpisode} left`}
            </span>
          </div>

          <div className={styles.storyboardContent}>
            {storyboardData && storyboardData.panels.length > 0 ? (
              <div className={styles.panelList}>
                {storyboardData.panels.map((panel, index) => (
                  <div key={index} className={styles.panelCard}>
                    <div className={styles.panelHeader}>
                      <span className={styles.panelIndex}>#{index + 1}</span>
                      {(panel.shot_size || panel.camera_angle) && (
                        <span className={styles.panelMeta}>
                          {panel.shot_size}
                          {panel.shot_size && panel.camera_angle ? ' / ' : ''}
                          {panel.camera_angle}
                        </span>
                      )}
                    </div>

                    {panel.scene && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>Scene</span>
                        <span className={styles.panelValue}>{panel.scene}</span>
                      </div>
                    )}

                    {panel.characters && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>Characters</span>
                        <span className={styles.panelValue}>{panel.characters}</span>
                      </div>
                    )}

                    {panel.dialogue && (
                      <div className={styles.panelDialogue}>
                        <span className={styles.panelLabel}>Dialogue</span>
                        <p className={styles.dialogueText}>"{panel.dialogue}"</p>
                      </div>
                    )}

                    {panel.effects && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>Effects</span>
                        <span className={styles.panelEffects}>{panel.effects}</span>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p className={styles.storyboardHint}>No storyboard data yet.</p>
            )}
          </div>

          <div className={styles.actionBar}>
            <button
              className={styles.primaryButton}
              onClick={handleConfirm}
              disabled={isSubmitting}
            >
              {isSubmitting
                ? 'Processing...'
                : currentEpisode === totalEpisodes
                  ? 'Confirm and Start Production'
                  : 'Confirm'}
            </button>
            <button
              className={styles.secondaryButton}
              onClick={() => setShowRevision(!showRevision)}
              disabled={isSubmitting}
            >
              {showRevision ? 'Cancel Revision' : 'Revise'}
            </button>
          </div>

          {showRevision && (
            <div className={styles.revisionPanel}>
              <textarea
                className={styles.revisionInput}
                placeholder="Enter revision feedback..."
                value={feedback}
                onChange={(e) => setFeedback(e.target.value)}
                rows={3}
                disabled={isSubmitting}
              />
              <div className={styles.revisionActions}>
                <button
                  className={styles.primaryButton}
                  onClick={handleRevise}
                  disabled={isSubmitting || !feedback.trim()}
                >
                  {isSubmitting ? 'Submitting...' : 'Submit Revision'}
                </button>
                <button
                  className={styles.secondaryButton}
                  onClick={() => {
                    setFeedback('');
                    setShowRevision(false);
                  }}
                  disabled={isSubmitting}
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* 阶段2-4：卡片网格视图 */}
      {workflowPhase !== 'review' && workflowPhase !== 'completed' && (
        <div className={styles.cardGrid}>
          {storyboardData?.panels.map((panel, idx) => (
            <Step5Card
              key={idx}
              panel={panel}
              panelIndex={idx}
              workflowPhase={workflowPhase}
              sceneImageState={sceneImageStates.get(idx)}
              fusionStatus={getFusionStatus(idx)}
              fusionUrl={getFusionUrl(idx)}
              videoStatus={getVideoStatus(idx)}
              videoUrl={getVideoUrl(idx)}
              onConfirm={handleConfirm}
              onRegenerateScene={() => handleRegenerateScene(idx)}
              onAutoFusion={() => handleAutoFusion(idx)}
              onManualFusion={() => handleOpenManualFusion(idx)}
              onGenerateVideo={() => handleGenerateVideo(idx)}
            />
          ))}
        </div>
      )}

      {/* 阶段5：完成态（跳转到 Step6） */}
      {workflowPhase === 'completed' && (
        <div className={styles.completedView}>
          <div className={styles.completedIcon}>✓</div>
          <h2>所有分镜处理完成</h2>
          <p>正在跳转到视频预览页面...</p>
        </div>
      )}

      {/* 底部操作栏 */}
      {showBottomBar && (
        <div className={styles.bottomActionBar}>
          <button
            className={styles.autoButton}
            onClick={handleAutoContinueAll}
            disabled={autoContinuing}
          >
            {autoContinuing ? '执行中...' : '一键自动化执行'}
          </button>
          <button className={styles.composeButton} onClick={handleCompose}>
            统一合并生成最终视频
          </button>
        </div>
      )}

      {!isGenerating && !isFailed && totalEpisodes === 0 && (
        <div className={styles.startSection}>
          <p className={styles.startHint}>
            Assets are ready. Click below to start generating storyboard episodes.
          </p>
          <button
            className={styles.primaryButton}
            onClick={handleStartProduction}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Processing...' : 'Start Storyboard'}
          </button>
        </div>
      )}

      {!isGenerating && !isFailed && allConfirmed && workflowPhase === 'review' && (
        <div className={styles.startSection}>
          <div className={styles.allConfirmed}>
            <div className={styles.allConfirmedIcon}>&#10003;</div>
            <p>All {totalEpisodes} episodes are confirmed.</p>
          </div>
          <button
            className={styles.productionButton}
            onClick={handleStartProduction}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Processing...' : 'Start Production'}
          </button>
        </div>
      )}

      {/* 手动融合模态框 */}
      {manualFusionPanelIndex !== null && reviewEpisodeId && (
        <div className={styles.modalOverlay}>
          <GridFusionEditor
            episodeId={reviewEpisodeId}
            onFusionSubmitted={handleFusionSubmitted}
            mode="modal"
            onClose={handleCloseManualFusion}
          />
        </div>
      )}

      {apiError && (
        <div className={styles.apiError}>
          <span>{apiError}</span>
          <button className={styles.errorDismiss} onClick={() => setApiError(null)}>x</button>
        </div>
      )}
    </div>
  );
};

export default Step5page;