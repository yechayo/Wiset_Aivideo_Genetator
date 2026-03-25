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

// 步骤顺序
const STAGES: ProductionStage[] = ['background', 'fusion', 'transition', 'video'];

export default function PanelProductionPage() {
  const { projectId, episodeId, panelIndex } = useParams<{
    projectId: string;
    episodeId: string;
    panelIndex: string;
  }>();
  const navigate = useNavigate();
  const panelIdx = parseInt(panelIndex || '0', 10);

  const { panel, isLoading, error, loadPanelState, setOperating, setError, reset, isOperating } =
    usePanelProductionStore();

  const [storyboard, setStoryboard] = useState<any>(null);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);
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

  // Auto-advance step when current step completes
  useEffect(() => {
    if (!panel) return;

    const currentStage = STAGES[currentStepIndex];
    const currentStatus = panel[`${currentStage}Status` as keyof typeof panel] as PanelStageStatus;

    if (currentStatus === 'completed' && currentStepIndex < STAGES.length - 1) {
      // Auto advance to next step
      setCurrentStepIndex(currentStepIndex + 1);
    }
  }, [panel, currentStepIndex]);

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
  const currentStage = STAGES[currentStepIndex];

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

  // Handle step navigation
  const handlePrevious = useCallback(() => {
    if (currentStepIndex > 0) {
      setCurrentStepIndex(currentStepIndex - 1);
    }
  }, [currentStepIndex]);

  const handleNext = useCallback(() => {
    if (currentStepIndex < STAGES.length - 1) {
      setCurrentStepIndex(currentStepIndex + 1);
    }
  }, [currentStepIndex]);

  // Handle one-click execute all
  const handleExecuteAll = useCallback(async () => {
    if (!episodeId) return;
    setOperating(true);
    setError(null);
    try {
      await produceSinglePanel(episodeId, panelIdx);
      await loadPanelState(parseInt(episodeId), panelIdx);
    } catch (e: any) {
      setError(e.message || '一键生成失败');
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

  // Render current step content
  const renderStepContent = () => {
    switch (currentStage) {
      case 'background':
        return (
          <BackgroundPanel
            status={panel?.backgroundStatus || 'pending'}
            imageUrl={panel?.backgroundUrl ?? null}
            prompt={null}
            onGenerate={async () => {
              setOperating(true);
              setError(null);
              try {
                await generateBackground(episodeId!, panelIdx);
                await loadPanelState(parseInt(episodeId!), panelIdx);
              } catch (e: any) {
                setError(e.message || '生成背景图失败');
              } finally {
                setOperating(false);
              }
            }}
          />
        );
      case 'fusion':
        return (
          <FusionPanel
            status={panel?.fusionStatus || 'pending'}
            imageUrl={panel?.fusionUrl ?? null}
            onGenerate={async () => {
              setOperating(true);
              setError(null);
              try {
                await generateFusion(episodeId!, panelIdx, {
                  backgroundUrl: panel?.backgroundUrl || '',
                  characterRefs: [],
                });
                await loadPanelState(parseInt(episodeId!), panelIdx);
              } catch (e: any) {
                setError(e.message || '生成融合图失败');
              } finally {
                setOperating(false);
              }
            }}
            onConfirm={async () => {
              setOperating(true);
              setError(null);
              try {
                await generateTransition(episodeId!, panelIdx, { fusionUrl: panel?.fusionUrl || '' });
                await loadPanelState(parseInt(episodeId!), panelIdx);
              } catch (e: any) {
                setError(e.message || '生成过渡融合图失败');
              } finally {
                setOperating(false);
              }
            }}
          />
        );
      case 'transition':
        return (
          <TransitionPanel
            status={panel?.transitionStatus || 'pending'}
            imageUrl={panel?.transitionUrl ?? null}
            hasTailFrame={panelIdx > 0}
          />
        );
      case 'video':
        return (
          <VideoPanel
            status={panel?.videoStatus || 'pending'}
            videoUrl={panel?.videoUrl ?? null}
            duration={panel?.videoDuration ?? null}
            onGenerate={async () => {
              setOperating(true);
              setError(null);
              try {
                await produceSinglePanel(episodeId!, panelIdx);
                await loadPanelState(parseInt(episodeId!), panelIdx);
              } catch (e: any) {
                setError(e.message || '生成视频失败');
              } finally {
                setOperating(false);
              }
            }}
          />
        );
      default:
        return null;
    }
  };

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
          currentStage={currentStage}
          stageStatuses={stageStatuses}
        />
      )}

      {/* Current Step Content Area */}
      <div className={styles.stepContentArea}>
        <h3 className={styles.stepTitle}>
          {currentStage === 'background' && '背景图生成'}
          {currentStage === 'fusion' && '融合图生成'}
          {currentStage === 'transition' && '过渡融合图'}
          {currentStage === 'video' && '视频生成'}
        </h3>
        <div className={styles.stepContent}>
          {renderStepContent()}
        </div>
      </div>

      {/* Navigation Buttons */}
      <div className={styles.navigation}>
        <button
          className={styles.secondaryBtn}
          onClick={handlePrevious}
          disabled={currentStepIndex === 0}
        >
          上一步
        </button>
        <button
          className={styles.primaryBtn}
          onClick={handleExecuteAll}
          disabled={isOperating}
        >
          {isOperating ? '执行中...' : '一键执行'}
        </button>
        <button
          className={styles.secondaryBtn}
          onClick={handleNext}
          disabled={currentStepIndex === STAGES.length - 1}
        >
          下一步
        </button>
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
