import { useEffect, useState, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import styles from './PanelProductionPage.module.less';
import { usePanelProductionStore } from '../../../stores/panelProductionStore';
import {
  generateBackground,
  generateFusion,
  generateTransition,
  produceSinglePanel,
} from '../../../services/panelProductionService';
import { getStoryboard } from '../../../services/projectService';
import { isApiSuccess } from '../../../services/apiClient';
import type { ProductionStage, PanelStageStatus } from '../../../services/types/episode.types';
import PanelInfoCard from './components/PanelInfoCard';
import ProductionPipeline from './components/ProductionPipeline';
import BackgroundPanel from './components/BackgroundPanel';
import FusionPanel from './components/FusionPanel';
import TransitionPanel from './components/TransitionPanel';
import VideoPanel from './components/VideoPanel';

const POLL_INTERVAL = 3000;

export default function PanelProductionPage() {
  const { projectId, episodeId, panelIndex } = useParams<{
    projectId: string;
    episodeId: string;
    panelIndex: string;
  }>();
  const navigate = useNavigate();
  const panelIdx = parseInt(panelIndex || '0', 10);

  const { panel, isLoading, error, loadPanelState, setOperating, setError, reset } =
    usePanelProductionStore();

  const [storyboard, setStoryboard] = useState<any>(null);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Load storyboard data
  useEffect(() => {
    if (!episodeId) return;
    getStoryboard(episodeId).then((res) => {
      if (isApiSuccess(res) && res.data?.storyboardJson) {
        try {
          const parsed = JSON.parse(res.data.storyboardJson);
          if (parsed?.panels?.length > 0) {
            setStoryboard(parsed);
          }
        } catch { /* ignore */ }
      }
    });
  }, [episodeId]);

  // Load panel state
  useEffect(() => {
    if (!episodeId) return;
    loadPanelState(parseInt(episodeId), panelIdx);
    return () => { reset(); };
  }, [episodeId, panelIdx, loadPanelState, reset]);

  // Start polling when generating
  const isPollingRef = useRef(false);
  useEffect(() => {
    if (!episodeId || !panel) return;

    const shouldPoll = panel.overallStatus === 'in_progress';

    if (shouldPoll && !isPollingRef.current) {
      isPollingRef.current = true;
      pollingRef.current = setInterval(() => {
        loadPanelState(parseInt(episodeId), panelIdx);
      }, POLL_INTERVAL);
    } else if (!shouldPoll && isPollingRef.current) {
      isPollingRef.current = false;
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    }

    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
      isPollingRef.current = false;
    };
  }, [episodeId, panelIdx, panel?.overallStatus, loadPanelState]);

  const currentPanel = storyboard?.panels?.[panelIdx];

  const formatCharacters = (chars: any): string => {
    if (!chars) return '';
    if (typeof chars === 'string') return chars;
    if (!Array.isArray(chars)) return '';
    return chars.map((c: any) => c.name || c.char_id || '').filter(Boolean).join(' / ');
  };

  const formatDialogue = (dlg: any): string => {
    if (!dlg) return '';
    if (typeof dlg === 'string') return dlg;
    if (!Array.isArray(dlg)) return '';
    return dlg.map((d: any) => {
      if (!d) return '';
      const speaker = d.speaker?.trim();
      const text = d.text?.trim();
      return speaker ? `${speaker}: ${text}` : text;
    }).filter(Boolean).join(' / ');
  };

  const handleGenerateBackground = useCallback(async () => {
    if (!episodeId) return;
    setOperating(true);
    setError(null);
    try {
      await generateBackground(episodeId, panelIdx);
      await loadPanelState(parseInt(episodeId), panelIdx);
    } catch (e: any) {
      setError(e.message || '生成背景图失败');
    } finally {
      setOperating(false);
    }
  }, [episodeId, panelIdx, setOperating, setError, loadPanelState]);

  const handleGenerateFusion = useCallback(async () => {
    if (!episodeId || !panel?.backgroundUrl) return;
    setOperating(true);
    setError(null);
    try {
      await generateFusion(episodeId, panelIdx, {
        backgroundUrl: panel.backgroundUrl,
        characterRefs: [],
      });
      await loadPanelState(parseInt(episodeId), panelIdx);
    } catch (e: any) {
      setError(e.message || '生成融合图失败');
    } finally {
      setOperating(false);
    }
  }, [episodeId, panelIdx, panel, setOperating, setError, loadPanelState]);

  const handleConfirmFusion = useCallback(async () => {
    if (!episodeId || !panel?.fusionUrl) return;
    setOperating(true);
    setError(null);
    try {
      await generateTransition(episodeId, panelIdx, { fusionUrl: panel.fusionUrl });
      await loadPanelState(parseInt(episodeId), panelIdx);
    } catch (e: any) {
      setError(e.message || '生成过渡融合图失败');
    } finally {
      setOperating(false);
    }
  }, [episodeId, panelIdx, panel, setOperating, setError, loadPanelState]);

  const handleGenerateVideo = useCallback(async () => {
    if (!episodeId) return;
    setOperating(true);
    setError(null);
    try {
      await produceSinglePanel(episodeId, panelIdx);
      // Start polling
      await loadPanelState(parseInt(episodeId), panelIdx);
    } catch (e: any) {
      setError(e.message || '生成视频失败');
    } finally {
      setOperating(false);
    }
  }, [episodeId, panelIdx, setOperating, setError, loadPanelState]);

  const handleBack = useCallback(() => {
    if (projectId) {
      navigate(`/project/${projectId}/step/5`);
    }
  }, [projectId, navigate]);

  const stageStatuses: Record<ProductionStage, PanelStageStatus> = panel
    ? {
        background: panel.backgroundStatus,
        fusion: panel.fusionStatus,
        transition: panel.transitionStatus,
        video: panel.videoStatus,
      }
    : { background: 'pending', fusion: 'pending', transition: 'pending', video: 'pending' };

  if (isLoading && !panel) {
    return <div className={styles.loading}><div className={styles.spinner} />加载中...</div>;
  }

  return (
    <div className={styles.page}>
      {/* Header */}
      <div className={styles.header}>
        <button className={styles.backBtn} onClick={handleBack}>← 返回</button>
        <h1 className={styles.title}>
          分镜 #{panelIdx + 1} 视频生产
        </h1>
      </div>

      {/* Panel Info */}
      {currentPanel && (
        <PanelInfoCard
          scene={currentPanel.scene || currentPanel.background?.scene_desc || ''}
          shotSize={currentPanel.shot_size || currentPanel.shot_type || ''}
          cameraAngle={currentPanel.camera_angle || ''}
          characters={formatCharacters(currentPanel.characters)}
          dialogue={formatDialogue(currentPanel.dialogue)}
        />
      )}

      {/* Pipeline */}
      {panel && (
        <ProductionPipeline
          currentStage={panel.currentStage}
          stageStatuses={stageStatuses}
        />
      )}

      {/* Current Stage Content */}
      <div className={styles.stageContent}>
        <BackgroundPanel
          status={panel?.backgroundStatus || 'pending'}
          imageUrl={panel?.backgroundUrl}
          prompt={null}
          onGenerate={handleGenerateBackground}
        />
        <FusionPanel
          status={panel?.fusionStatus || 'pending'}
          imageUrl={panel?.fusionUrl}
          onGenerate={handleGenerateFusion}
          onConfirm={handleConfirmFusion}
        />
        <TransitionPanel
          status={panel?.transitionStatus || 'pending'}
          imageUrl={panel?.transitionUrl}
          hasTailFrame={panelIdx > 0}
        />
        <VideoPanel
          status={panel?.videoStatus || 'pending'}
          videoUrl={panel?.videoUrl}
          duration={panel?.videoDuration}
          onGenerate={handleGenerateVideo}
        />
      </div>

      {/* Error */}
      {error && (
        <div className={styles.error}>
          <span>{error}</span>
          <button className={styles.errorDismiss} onClick={() => setError(null)}>×</button>
        </div>
      )}
    </div>
  );
}
