import { useEffect, useState, useCallback, useRef } from 'react';
import styles from './Step5page.module.less';
import type { StepContentProps } from '../types';
import type { Project } from '../../../services';
import { useCreateStore } from '../../../stores/createStore';
import {
  getEpisodes,
  getPanelStates,
  getProductionPipeline,
} from '../../../services/episodeService';
import {
  getStoryboard,
  startStoryboard,
  confirmStoryboard,
  reviseStoryboard,
  retryStoryboard,
} from '../../../services/projectService';
import {
  autoProduceAll,
  produceSinglePanel,
} from '../../../services/panelProductionService';
import { isApiSuccess } from '../../../services/apiClient';
import type { EpisodeCardData } from '../../../services/types/episode.types';
import EpisodeCard from './components/EpisodeCard';

const PANEL_POLL_INTERVAL = 5000;

interface Step5pageProps extends StepContentProps {
  project: Project;
}

const Step5page = ({ project }: Step5pageProps) => {
  const { statusInfo, syncStatus } = useCreateStore();
  const projectId = project.projectId;

  const [episodes, setEpisodes] = useState<EpisodeCardData[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [apiError, setApiError] = useState<string | null>(null);

  const panelPollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const initializedRef = useRef(false);

  // 加载所有剧集的基础信息
  const loadEpisodes = useCallback(async () => {
    if (!projectId) return;
    try {
      const res = await getEpisodes(projectId);
      if (res.code === 200 && Array.isArray(res.data)) {
        setEpisodes(res.data.map((ep: any) => ({
          id: ep.id,
          episodeNum: ep.episodeNum || 1,
          title: ep.title || `Episode ${ep.episodeNum || 1}`,
          status: ep.status || 'DRAFT',
          productionStatus: ep.productionStatus || null,
          finalVideoUrl: ep.finalVideoUrl || null,
          errorMsg: ep.errorMsg || null,
          storyboardJson: ep.storyboardJson || null,
          storyboardStatus: null,
          panelStates: [],
          sceneGridUrls: [],
          loading: false,
          loadError: null,
        })));
      }
    } catch (e: any) {
      console.error('加载剧集列表失败:', e);
    }
  }, [projectId]);

  // 加载单个剧集的面板状态（生产模式用）
  const loadPanelStates = useCallback(async (epId: number): Promise<EpisodeCardData['panelStates']> => {
    try {
      const res = await getPanelStates(String(epId));
      if (res.code === 200 && res.data) return res.data;
    } catch (e) {
      console.error(`加载面板状态失败 episodeId=${epId}:`, e);
    }
    return [];
  }, []);

  // 加载管线状态（获取 sceneGridUrls）
  const loadPipelineForSceneGrids = useCallback(async (): Promise<string[]> => {
    if (!projectId) return [];
    try {
      const res = await getProductionPipeline(projectId);
      if (res.code === 200 && res.data) return res.data.sceneGridUrls || [];
    } catch (e) {
      console.error('加载管线状态失败:', e);
    }
    return [];
  }, [projectId]);

  // 轮询所有进行中剧集的面板状态
  const pollPanelStates = useCallback(async () => {
    setEpisodes(prev => {
      // 找出需要轮询的剧集（已确认分镜且在生产中的）
      const episodesToPoll = prev.filter(
        ep => (ep.status === 'DONE' || ep.productionStatus === 'IN_PROGRESS') && !ep.loading
      );
      if (episodesToPoll.length === 0) return prev;

      // 标记加载中
      const updated = prev.map(ep => ({
        ...ep,
        loading: episodesToPoll.some(p => p.id === ep.id) ? true : ep.loading,
      }));
      return updated;
    });

    // 并行加载所有剧集的面板状态
    const currentEpisodes = episodes; // capture
    const promises = currentEpisodes
      .filter(ep => (ep.status === 'DONE' || ep.productionStatus === 'IN_PROGRESS'))
      .map(async (ep) => {
        const [panelStates] = await Promise.all([
          loadPanelStates(ep.id),
        ]);
        return { id: ep.id, panelStates };
      });

    const results = await Promise.all(promises);
    const resultMap = new Map(results.map(r => [r.id, r.panelStates]));

    // 同时获取 sceneGridUrls
    const sceneGridUrls = await loadPipelineForSceneGrids();

    setEpisodes(prev => prev.map(ep => ({
      ...ep,
      panelStates: resultMap.get(ep.id) || ep.panelStates,
      sceneGridUrls: sceneGridUrls,
      loading: false,
    })));
  }, [episodes, loadPanelStates, loadPipelineForSceneGrids]);

  // 首次加载
  useEffect(() => {
    if (!projectId || initializedRef.current) return;
    initializedRef.current = true;
    loadEpisodes();
  }, [projectId, loadEpisodes]);

  // 项目状态变化时重新加载剧集列表
  useEffect(() => {
    if (!statusInfo) return;
    loadEpisodes();
  }, [statusInfo?.storyboardCurrentEpisode, statusInfo?.storyboardAllConfirmed, statusInfo?.statusCode, loadEpisodes]);

  // 面板状态轮询
  useEffect(() => {
    const hasActiveProduction = episodes.some(
      ep => ep.productionStatus === 'IN_PROGRESS' || ep.status === 'DONE'
    );
    if (!hasActiveProduction) {
      if (panelPollRef.current) {
        clearInterval(panelPollRef.current);
        panelPollRef.current = null;
      }
      return;
    }

    if (panelPollRef.current) clearInterval(panelPollRef.current);
    panelPollRef.current = setInterval(pollPanelStates, PANEL_POLL_INTERVAL);
    return () => {
      if (panelPollRef.current) {
        clearInterval(panelPollRef.current);
        panelPollRef.current = null;
      }
    };
  }, [episodes, pollPanelStates]);

  // 错误提示自动消失
  useEffect(() => {
    if (!apiError) return;
    const timer = setTimeout(() => setApiError(null), 5000);
    return () => clearTimeout(timer);
  }, [apiError]);

  // 确认分镜
  const handleConfirm = useCallback(async (episodeId: string) => {
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await confirmStoryboard(episodeId);
      if (isApiSuccess(res)) {
        if (projectId) await syncStatus(projectId);
        return;
      }
      setApiError(res.message || '确认失败');
    } catch (e: any) {
      setApiError(e.message || '确认失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  // 修订分镜
  const handleRevise = useCallback(async (episodeId: string, feedback: string) => {
    if (!feedback.trim()) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await reviseStoryboard(episodeId, feedback.trim());
      if (isApiSuccess(res)) {
        if (projectId) await syncStatus(projectId);
        return;
      }
      setApiError(res.message || '修订失败');
    } catch (e: any) {
      setApiError(e.message || '修订失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  // 重试分镜生成
  const handleRetry = useCallback(async (episodeId: string) => {
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await retryStoryboard(episodeId);
      if (isApiSuccess(res)) {
        if (projectId) await syncStatus(projectId);
        return;
      }
      setApiError(res.message || '重试失败');
    } catch (e: any) {
      setApiError(e.message || '重试失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  // 启动分镜生成
  const handleStartStoryboard = useCallback(async () => {
    if (!projectId) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await startStoryboard(projectId);
      if (!isApiSuccess(res)) {
        setApiError(res.message || '启动失败');
      }
      if (projectId) await syncStatus(projectId);
    } catch (e: any) {
      setApiError(e.message || '启动失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  // 单格生成视频
  const handleGenerateVideo = useCallback(async (episodeId: string, panelIndex: number) => {
    try {
      await produceSinglePanel(episodeId, panelIndex);
      await pollPanelStates();
    } catch (e: any) {
      setApiError(e.message || '生成视频失败');
    }
  }, [pollPanelStates]);

  // 一键自动化
  const handleAutoContinue = useCallback(async (episodeId: string) => {
    try {
      await autoProduceAll(episodeId);
      await pollPanelStates();
    } catch (e: any) {
      setApiError(e.message || '自动化执行失败');
    }
  }, [pollPanelStates]);

  // 加载单个剧集的分镜数据
  const handleLoadStoryboard = useCallback(async (episodeId: string) => {
    try {
      const res = await getStoryboard(episodeId);
      if (res.code === 200 && res.data) {
        const { storyboardJson, status } = res.data;
        setEpisodes(prev => prev.map(ep =>
          String(ep.id) === episodeId
            ? { ...ep, storyboardJson: storyboardJson || null, storyboardStatus: status || null }
            : ep
        ));
      }
    } catch (e) {
      console.error('加载分镜失败:', e);
    }
  }, []);

  const currentReviewId = statusInfo?.storyboardReviewEpisodeId ?? null;
  const totalEpisodes = episodes.length;

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>剧集工作台</h1>
        <p className={styles.subtitle}>
          管理所有剧集的分镜审核与视频生成
          {totalEpisodes > 0 && ` · 共 ${totalEpisodes} 集`}
        </p>
      </div>

      {episodes.length === 0 && (
        <div className={styles.emptyState}>
          <p className={styles.emptyHint}>
            剧集数据为空，请先完成前置步骤。
          </p>
          <button
            className={styles.primaryButton}
            onClick={handleStartStoryboard}
            disabled={isSubmitting}
          >
            {isSubmitting ? '处理中...' : '开始生成分镜'}
          </button>
        </div>
      )}

      <div className={styles.cardList}>
        {episodes.map((ep) => (
          <EpisodeCard
            key={ep.id}
            episode={ep}
            isCurrentReview={String(ep.id) === currentReviewId}
            isLoadingStoryboard={statusInfo?.isGenerating ?? false}
            storyboardStatusDesc={currentReviewId === String(ep.id) ? (statusInfo?.statusDescription || '') : ''}
            storyboardFailed={currentReviewId === String(ep.id) ? (statusInfo?.isFailed ?? false) : false}
            sceneGridUrls={ep.sceneGridUrls}
            onConfirm={() => handleConfirm(String(ep.id))}
            onRevise={(feedback) => handleRevise(String(ep.id), feedback)}
            onRetry={() => handleRetry(String(ep.id))}
            onLoadStoryboard={() => handleLoadStoryboard(String(ep.id))}
            onGenerateVideo={(panelIndex) => handleGenerateVideo(String(ep.id), panelIndex)}
            onAutoContinue={() => handleAutoContinue(String(ep.id))}
            isGlobalSubmitting={isSubmitting}
          />
        ))}
      </div>

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
