/**
 * Step4Production - 分镜生产 Tab 子阶段
 * Tab 4a: 脚本生成/审核
 * Tab 4b: 九宫格图片生成/审核
 * Tab 4c: 视频生成/确认
 */
import { useState, useCallback, useEffect, useRef } from 'react';
import styles from './Step4Production.module.less';
import type { ChapterState, EpisodeState, SegmentState } from './types';
import {
  getEpisodes,
  getPanels,
  getBatchProductionStatuses,
  approveStoryboard,
  rejectStoryboard,
  approveEpisodeGrid,
  rejectEpisodeGrid,
  regenerateEpisodeGrid,
  generateVideo,
} from '../../../services/episodeService';
import { advanceStatus } from '../../../services/projectService';
import { useCreateStore } from '../../../stores/createStore';
import { useSseProgress } from './hooks/useSseProgress';

type SubPhase = 'script' | 'grid' | 'video';

const VIDEO_POLL_INTERVAL = 3000;
const VIDEO_POLL_MAX_RETRIES = 720;

interface Step4ProductionProps {
  project: any;
  onNextStep?: () => void;
}

export default function Step4Production({ project, onNextStep }: Step4ProductionProps) {
  const projectId = project?.projectId;
  const { statusInfo, syncStatus } = useCreateStore();

  // Tab state
  const [activeTab, setActiveTab] = useState<SubPhase>('script');

  // Data state
  const [chapters, setChapters] = useState<ChapterState[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // UI state
  const [expandedEpisodeId, setExpandedEpisodeId] = useState<number | null>(null);
  const [generatingScript, setGeneratingScript] = useState<number | null>(null);
  const [generatingGrid, setGeneratingGrid] = useState<number | null>(null);
  const [generatingVideo, setGeneratingVideo] = useState<string | null>(null); // "episodeId-panelId"
  const [confirmingEpisodeId, setConfirmingEpisodeId] = useState<number | null>(null);

  // Off-peak mode
  const [offPeak, setOffPeak] = useState(() => localStorage.getItem('video_off_peak') === 'true');
  const toggleOffPeak = useCallback(() => {
    setOffPeak(prev => {
      const next = !prev;
      localStorage.setItem('video_off_peak', String(next));
      return next;
    });
  }, []);

  // Refs
  const panelsLoadedRef = useRef<Set<number>>(new Set());
  const generateVideoAbortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    return () => {
      generateVideoAbortRef.current?.abort();
    };
  }, []);

  // ==================== Data Loading ====================

  const loadEpisodes = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await getEpisodes(projectId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) {
        throw new Error(res.message || '加载失败');
      }
      const items = res.data.items || [];

      // Group by chapter
      const chapterMap = new Map<string, any[]>();
      items.forEach((ep: any) => {
        const chapterTitle = ep.episodeInfo?.chapterTitle?.trim() || '未分章';
        if (!chapterMap.has(chapterTitle)) chapterMap.set(chapterTitle, []);
        chapterMap.get(chapterTitle)!.push(ep);
      });

      const builtChapters: ChapterState[] = [];
      let chapterIndex = 0;
      for (const [chapterTitle, chapterEpisodes] of chapterMap.entries()) {
        chapterIndex++;
        const episodeStates: EpisodeState[] = chapterEpisodes.map((ep: any, idx: number) => {
          // Parse scene_summary from panelPlan
          let sceneSummaryMap: Record<string, string> = {};
          try {
            const planStr = ep.episodeInfo?.panelPlan;
            if (planStr) {
              const plan = JSON.parse(planStr);
              if (Array.isArray(plan?.panels)) {
                plan.panels.forEach((p: any) => {
                  if (p.panel_id && p.scene_summary) {
                    sceneSummaryMap[p.panel_id] = p.scene_summary;
                  }
                });
              }
            }
          } catch { /* ignore */ }

          // Build segments from shots (script data)
          const textSegments: SegmentState[] = [];
          if (Array.isArray(ep.episodeInfo?.shots)) {
            ep.episodeInfo.shots.forEach((shot: any, sIdx: number) => {
              textSegments.push({
                segmentIndex: sIdx,
                title: `分镜 ${sIdx + 1}`,
                synopsis: shot.scene_summary || shot.visual_description || '',
                sceneThumbnail: null,
                characterAvatars: (shot.characters || []).map((name: string) => ({ charId: '', name, avatarUrl: '' })),
                pipelineStep: 'pending',
                gridImages: [],
                gridStatus: 'pending',
                fusionImageUrl: null,
                shots: [shot],
                videoUrl: null,
                feedback: '',
                panelData: {
                  panelId: '',
                  planPanelId: '',
                  composition: shot.composition || '',
                  shotType: shot.shot_type,
                  cameraAngle: shot.camera_angle,
                  pacing: shot.pacing,
                  dialogue: Array.isArray(shot.dialogue)
                    ? shot.dialogue.map((d: any) => d.speaker ? `${d.speaker}：${d.text}` : d.text).join('\n')
                    : '',
                  characters: (shot.characters || []).map((name: string) => ({ name })),
                  background: shot.background || {},
                  imagePromptHint: shot.image_prompt_hint,
                  sfx: shot.sfx || [],
                  duration: shot.duration,
                  totalShots: 1,
                  totalDuration: shot.duration || 0,
                  visualStyle: ep.episodeInfo?.visualStyle,
                },
              });
            });
          }

          return {
            episodeId: ep.id,
            episodeIndex: ep.episodeInfo?.episodeNum || idx + 1,
            title: ep.episodeInfo?.title,
            sceneSummaryMap,
            segments: textSegments,
            gridStatus: ep.episodeInfo?.gridStatus,
            gridImages: ep.episodeInfo?.gridImages || [],
            splitShots: ep.episodeInfo?.splitShots || [],
            gridRejectionFeedback: ep.episodeInfo?.gridRejectionFeedback || null,
            isNewFlow: !!ep.episodeInfo?.gridStatus,
            scriptStatus: ep.episodeInfo?.scriptStatus || 'pending',
          };
        });

        builtChapters.push({ chapterIndex, title: chapterTitle, episodes: episodeStates });
      }
      setChapters(builtChapters);

      // Load panels for all episodes
      panelsLoadedRef.current.clear();
      const allIds = builtChapters.flatMap(ch => ch.episodes.map(ep => ep.episodeId));
      allIds.forEach(eid => { loadPanelsForEpisode(eid); refreshProductionStatuses(eid); });
    } catch (err: any) {
      setError(err?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => { loadEpisodes(); }, [loadEpisodes]);

  // ==================== Panel Loading ====================

  const loadPanelsForEpisode = useCallback(async (episodeId: number) => {
    if (!projectId || panelsLoadedRef.current.has(episodeId)) return;
    panelsLoadedRef.current.add(episodeId);
    try {
      const res = await getPanels(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const panels = res.data || [];

      const segments: SegmentState[] = panels.map((panel: any, idx: number) => {
        const info = panel.panelInfo || {};
        const shots = info.shots || [];
        const isGroupedPanel = shots.length > 1;
        const synopsis = isGroupedPanel
          ? `${shots.length} 个分镜`
          : (info.scene_summary || shots[0]?.visualDescription || '');
        const thumbnail = info.fusionImageUrl || shots[0]?.splitImageUrl || (info.gridImages?.[0] || null);

        return {
          segmentIndex: idx,
          title: isGroupedPanel ? `分组 ${idx + 1}` : `分镜 ${idx + 1}`,
          synopsis,
          sceneThumbnail: thumbnail,
          characterAvatars: [],
          pipelineStep: mapToPipelineStep(info.gridStatus, info.videoStatus),
          gridImages: info.gridImages || [],
          gridStatus: info.gridStatus || 'pending',
          fusionImageUrl: info.fusionImageUrl || null,
          shots,
          videoUrl: info.videoUrl || null,
          feedback: info.revisionFeedback || '',
          panelData: {
            panelId: String(panel.id),
            planPanelId: info.panel_id || '',
            composition: info.composition || '',
            shotType: info.shot_type,
            cameraAngle: info.camera_angle,
            pacing: info.pacing,
            dialogue: Array.isArray(info.dialogue)
              ? info.dialogue.map((d: any) => d.speaker ? `${d.speaker}：${d.text}` : d.text).join('\n')
              : '',
            characters: info.characters || [],
            background: info.background || {},
            imagePromptHint: info.image_prompt_hint,
            sfx: info.sfx || [],
            duration: info.duration || info.totalDuration,
            totalShots: info.totalShots,
            totalDuration: info.totalDuration,
            visualStyle: info.visualStyle,
          },
        };
      });

      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep => ep.episodeId === episodeId ? { ...ep, segments } : ep),
      })));
    } catch (err) {
      console.error(`加载集 ${episodeId} 分镜失败:`, err);
    }
  }, [projectId]);

  const refreshProductionStatuses = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    try {
      const res = await getBatchProductionStatuses(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const statusMap = new Map<number, any>();
      res.data.forEach((s: any) => statusMap.set(s.panelId, s));

      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === episodeId
            ? {
                ...ep,
                segments: ep.segments.map(seg => {
                  const panelId = Number(seg.panelData?.panelId);
                  const status = statusMap.get(panelId);
                  if (!status) return seg;
                  return {
                    ...seg,
                    pipelineStep: mapToPipelineStep(status.gridStatus, status.videoStatus),
                    gridImages: status.gridImages?.length ? status.gridImages : seg.gridImages,
                    gridStatus: status.gridStatus || seg.gridStatus,
                    fusionImageUrl: status.fusionImageUrl ?? seg.fusionImageUrl,
                    shots: status.shots?.length ? status.shots : seg.shots,
                    videoUrl: status.videoUrl ?? seg.videoUrl,
                  };
                }),
              }
            : ep
        ),
      })));
    } catch (err) {
      console.error('刷新生产状态失败:', err);
    }
  }, [projectId]);

  function mapToPipelineStep(gridStatus: string, videoStatus: string): SegmentState['pipelineStep'] {
    if (videoStatus === 'completed') return 'video_completed';
    if (videoStatus === 'failed') return 'video_failed';
    if (videoStatus === 'generating') return 'video_generating';
    if (gridStatus === 'approved') return 'grid_approved';
    if (gridStatus === 'generating') return 'grid_generating';
    if (gridStatus === 'generated' || gridStatus === 'rejected') return 'grid_review';
    return 'pending';
  }

  // ==================== SSE ====================

  useSseProgress(projectId, {
    onEpisodeScriptDone: (data) => {
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === data.episodeId || ep.episodeIndex === data.episodeNum
            ? { ...ep, episodeId: data.episodeId || ep.episodeId, scriptStatus: 'done' as const }
            : ep
        ),
      })));
      if (data.episodeId) {
        panelsLoadedRef.current.delete(data.episodeId);
        loadPanelsForEpisode(data.episodeId);
      }
    },
    onEpisodePanelDone: (data) => {
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === data.episodeId || ep.episodeIndex === data.episodeNum
            ? { ...ep, episodeId: data.episodeId || ep.episodeId }
            : ep
        ),
      })));
      if (data.episodeId) {
        panelsLoadedRef.current.delete(data.episodeId);
        loadPanelsForEpisode(data.episodeId);
      }
    },
    onEpisodeGridStatus: (data) => {
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          (ep.episodeId === data.episodeId || (data.episodeNum && ep.episodeIndex === data.episodeNum))
            ? { ...ep, episodeId: data.episodeId || ep.episodeId, gridStatus: data.gridStatus as any, isNewFlow: true }
            : ep
        ),
      })));
      if (data.episodeId && (data.gridStatus === 'generated' || data.gridStatus === 'approved' || data.gridStatus === 'failed')) {
        panelsLoadedRef.current.delete(data.episodeId);
        loadPanelsForEpisode(data.episodeId);
        refreshProductionStatuses(data.episodeId);
      }
    },
    onPanelVideoDone: (data) => {
      if (data.episodeId) refreshProductionStatuses(data.episodeId);
    },
    onPanelVideoFailed: (data) => {
      if (data.episodeId) refreshProductionStatuses(data.episodeId);
    },
    onStatusChange: (data) => {
      if (projectId && data.to) { syncStatus(projectId); loadEpisodes(); }
    },
    onReconnect: () => {
      if (projectId) { syncStatus(projectId); loadEpisodes(); }
    },
  });

  // ==================== Tab Unlock Logic ====================

  const allEpisodes = chapters.flatMap(ch => ch.episodes);

  // Tab 4b unlocked when ALL episodes have gridStatus === 'approved'
  const tab4bUnlocked = allEpisodes.length > 0 && allEpisodes.every(ep => ep.gridStatus === 'approved');

  // Tab 4c unlocked when ALL episodes have gridStatus === 'approved' AND all panels have videos
  const tab4cUnlocked = tab4bUnlocked && allEpisodes.every(ep =>
    ep.segments.length > 0 && ep.segments.every(seg => seg.videoUrl || seg.pipelineStep === 'video_completed')
  );

  // ==================== Actions ====================

  // Generate script per episode
  const handleGenerateScript = useCallback(async (episodeId: number) => {
    if (!projectId || generatingScript) return;
    setGeneratingScript(episodeId);
    try {
      // Call POST /projects/{projectId}/episodes/{episodeId}/script
      // Using generic fetch since this endpoint may not exist in episodeService yet
      const res = await fetch(`/api/projects/${projectId}/episodes/${episodeId}/script`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      });
      if (!res.ok) throw new Error('生成脚本失败');
      // Reload episodes after generation
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.message || '生成脚本失败');
    } finally {
      setGeneratingScript(null);
    }
  }, [projectId, generatingScript, loadEpisodes]);

  // Approve/reject script per episode
  const handleApproveScript = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    try {
      await approveStoryboard(projectId, episodeId);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '审核失败');
    }
  }, [projectId, loadEpisodes]);

  const handleRejectScript = useCallback(async (episodeId: number, reason: string) => {
    if (!projectId) return;
    try {
      await rejectStoryboard(projectId, episodeId, reason);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '退回失败');
    }
  }, [projectId, loadEpisodes]);

  // Generate grid per episode
  const handleGenerateGrid = useCallback(async (episodeId: number) => {
    if (!projectId || generatingGrid) return;
    setGeneratingGrid(episodeId);
    try {
      await regenerateEpisodeGrid(projectId, episodeId);
      // SSE will handle refresh
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '生成九宫格失败');
    } finally {
      setGeneratingGrid(null);
    }
  }, [projectId, generatingGrid]);

  // Approve/reject grid per episode
  const handleApproveGrid = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    try {
      await approveEpisodeGrid(projectId, episodeId);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '审核失败');
    }
  }, [projectId, loadEpisodes]);

  const handleRejectGrid = useCallback(async (episodeId: number, reason: string) => {
    if (!projectId) return;
    try {
      await rejectEpisodeGrid(projectId, episodeId, reason);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '退回失败');
    }
  }, [projectId, loadEpisodes]);

  // Generate video per panel
  const handleGenerateVideo = useCallback(async (episodeId: number, panelId: string) => {
    if (!projectId) return;
    const key = `${episodeId}-${panelId}`;
    setGeneratingVideo(key);

    const abort = new AbortController();
    generateVideoAbortRef.current = abort;
    let retries = 0;

    const poll = async () => {
      while (retries < VIDEO_POLL_MAX_RETRIES && !abort.signal.aborted) {
        await new Promise(r => setTimeout(r, VIDEO_POLL_INTERVAL));
        if (abort.signal.aborted) return;
        retries++;
        try {
          const res = await getBatchProductionStatuses(projectId, episodeId);
          if ((res.code !== 0 && res.code !== 200) || !res.data) continue;
          const panelStatus = res.data.find((s: any) => s.panelId === Number(panelId));
          if (panelStatus) {
            await refreshProductionStatuses(episodeId);
            if (panelStatus.videoStatus === 'completed') { setGeneratingVideo(null); return; }
            if (panelStatus.videoStatus === 'failed') { alert('视频生成失败'); setGeneratingVideo(null); return; }
          }
        } catch { /* continue polling */ }
      }
      if (retries >= VIDEO_POLL_MAX_RETRIES) console.warn('视频生成轮询超时');
      setGeneratingVideo(null);
    };
    poll();

    generateVideo(projectId, episodeId, Number(panelId), offPeak)
      .catch((err: any) => {
        abort.abort();
        alert(err?.response?.data?.message || err?.message || '生成视频失败');
        setGeneratingVideo(null);
      });
  }, [projectId, offPeak, refreshProductionStatuses]);

  // Batch generate videos for an episode
  const handleBatchGenerateVideo = useCallback(async (episodeId: number) => {
    const episode = chapters.flatMap(ch => ch.episodes).find(ep => ep.episodeId === episodeId);
    if (!episode) return;
    for (const seg of episode.segments) {
      const panelId = seg.panelData?.panelId;
      if (panelId && !seg.videoUrl) {
        await handleGenerateVideo(episodeId, panelId);
      }
    }
  }, [chapters, handleGenerateVideo]);

  // Confirm all panels done -> advance to Step 5
  const [advancing, setAdvancing] = useState(false);
  const handleConfirmPanels = useCallback(async () => {
    if (!projectId || advancing) return;
    const confirmed = window.confirm('确认所有分镜视频无误后，将进入视频合成阶段。是否继续？');
    if (!confirmed) return;
    setAdvancing(true);
    try {
      await advanceStatus(projectId, 'forward', 'confirm_panels');
      onNextStep?.();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '状态推进失败');
    } finally {
      setAdvancing(false);
    }
  }, [projectId, advancing, onNextStep]);

  // ==================== Render Helpers ====================

  const toggleEpisode = useCallback((episodeId: number) => {
    setExpandedEpisodeId(prev => prev === episodeId ? null : episodeId);
  }, []);

  // Stats
  const totalPanels = allEpisodes.reduce((sum, ep) => sum + ep.segments.length, 0);
  const completedVideos = allEpisodes.reduce((sum, ep) =>
    sum + ep.segments.filter(seg => seg.pipelineStep === 'video_completed').length, 0);

  // ==================== Render ====================

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

  if (error) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.errorState}>
          <p>{error}</p>
          <button onClick={loadEpisodes} className={styles.retryButton}>重试</button>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.pageContainer}>
      {/* Header */}
      <div className={styles.pageHeader}>
        <div className={styles.titleSection}>
          <h1 className={styles.pageTitle}>分镜生产</h1>
          <p className={styles.pageSubtitle}>脚本生成 → 九宫格图片 → 视频生成</p>
        </div>
      </div>

      {/* Tab Bar */}
      <div className={styles.tabBar}>
        <button
          className={`${styles.tab} ${activeTab === 'script' ? styles.active : ''}`}
          onClick={() => setActiveTab('script')}
        >
          4a 脚本生成
        </button>
        <button
          className={`${styles.tab} ${activeTab === 'grid' ? styles.active : ''} ${!tab4bUnlocked ? styles.locked : ''}`}
          onClick={() => tab4bUnlocked && setActiveTab('grid')}
          disabled={!tab4bUnlocked}
        >
          4b 九宫格图片
          {!tab4bUnlocked && <span className={styles.tabLock}>🔒</span>}
        </button>
        <button
          className={`${styles.tab} ${activeTab === 'video' ? styles.active : ''} ${!tab4cUnlocked ? styles.locked : ''}`}
          onClick={() => tab4cUnlocked && setActiveTab('video')}
          disabled={!tab4cUnlocked}
        >
          4c 视频生成
          {!tab4cUnlocked && <span className={styles.tabLock}>🔒</span>}
        </button>
      </div>

      {/* Stats Bar */}
      {activeTab === 'video' && (
        <div className={styles.statsBar}>
          <div className={styles.statsInfo}>
            <span className={styles.completedCount}>{completedVideos}</span>
            <span className={styles.separator}>/</span>
            <span className={styles.totalCount}>{totalPanels}</span>
            <span className={styles.statsLabel}>分镜视频已完成</span>
          </div>
          <button
            className={`${styles.offPeakToggle} ${offPeak ? styles.offPeakActive : ''}`}
            onClick={toggleOffPeak}
          >
            <span className={styles.toggleTrack}><span className={styles.toggleThumb} /></span>
            <span className={styles.toggleLabel}>错峰</span>
          </button>
        </div>
      )}

      {/* Tab Content */}
      <div className={styles.tabContent}>

        {/* ==================== Tab 4a: Script ==================== */}
        {activeTab === 'script' && (
          <div className={styles.scriptReviewSection}>
            {chapters.length === 0 ? (
              <div className={styles.emptyState}><p>暂无章节数据</p></div>
            ) : (
              chapters.map(chapter => (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <div className={styles.chapterHeader}>
                    <span className={styles.chapterIcon}>📖</span>
                    <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
                  </div>
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => (
                      <div key={ep.episodeId} className={styles.episodeScriptCard}>
                        <div className={styles.episodeScriptHeader}>
                          <div>
                            <h3 className={styles.episodeScriptTitle}>
                              第{ep.episodeIndex}集 {ep.title}
                            </h3>
                            <span className={styles.episodeScriptCount}>
                              {ep.segments.length > 0 ? `${ep.segments.length} 个分镜` : '暂无分镜数据'}
                            </span>
                          </div>
                          <div style={{ display: 'flex', gap: 8 }}>
                            <button
                              className={styles.scriptGenerateBtn}
                              onClick={() => handleGenerateScript(ep.episodeId)}
                              disabled={generatingScript === ep.episodeId}
                            >
                              {generatingScript === ep.episodeId ? '生成中...' : '生成脚本'}
                            </button>
                            {ep.segments.length > 0 && (
                              <>
                                <button
                                  className={styles.scriptGridBtn}
                                  onClick={() => handleApproveScript(ep.episodeId)}
                                >
                                  通过
                                </button>
                                <button
                                  className={styles.confirmButton}
                                  style={{ background: '#ef4444' }}
                                  onClick={() => {
                                    const reason = prompt('请输入退回原因:');
                                    if (reason) handleRejectScript(ep.episodeId, reason);
                                  }}
                                >
                                  退回
                                </button>
                              </>
                            )}
                          </div>
                        </div>

                        {/* Expand to show script text */}
                        <div
                          style={{ cursor: 'pointer', color: '#1677ff', fontSize: 13, marginTop: 8 }}
                          onClick={() => toggleEpisode(ep.episodeId)}
                        >
                          {expandedEpisodeId === ep.episodeId ? '收起' : '展开'}分镜文本 ▼
                        </div>

                        {expandedEpisodeId === ep.episodeId && ep.segments.length > 0 && (
                          <div style={{ marginTop: 12 }}>
                            {ep.segments.map((seg, idx) => (
                              <div key={idx} className={styles.scriptSegmentItem}>
                                <div className={styles.scriptSegmentTitle}>分镜 {idx + 1}</div>
                                {seg.synopsis && (
                                  <div className={styles.scriptSegmentDetail}>
                                    <span>概要：</span>{seg.synopsis}
                                  </div>
                                )}
                                {seg.panelData?.composition && (
                                  <div className={styles.scriptSegmentDetail}>
                                    <span>构图：</span>{seg.panelData.composition}
                                  </div>
                                )}
                                {seg.panelData?.dialogue && (
                                  <div className={styles.scriptSegmentDetail}>
                                    <span>对话：</span><span style={{ whiteSpace: 'pre-wrap' }}>{seg.panelData.dialogue}</span>
                                  </div>
                                )}
                                {seg.characterAvatars.length > 0 && (
                                  <div className={styles.scriptSegmentCharacters}>
                                    <span>角色：</span>{seg.characterAvatars.map(a => a.name).join('、')}
                                  </div>
                                )}
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              ))
            )}
          </div>
        )}

        {/* ==================== Tab 4b: Grid ==================== */}
        {activeTab === 'grid' && (
          <div>
            {chapters.length === 0 ? (
              <div className={styles.emptyState}><p>暂无章节数据</p></div>
            ) : (
              chapters.map(chapter => (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <div className={styles.chapterHeader}>
                    <span className={styles.chapterIcon}>📖</span>
                    <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
                  </div>
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => (
                      <div key={ep.episodeId} style={{
                        padding: 16, marginBottom: 12, background: '#fff', borderRadius: 8,
                        border: '1px solid #f0f0f0'
                      }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                          <div>
                            <h3 style={{ margin: '0 0 4px 0', fontSize: 15, fontWeight: 600 }}>
                              第{ep.episodeIndex}集 {ep.title}
                            </h3>
                            <span style={{ fontSize: 13, color: '#999' }}>
                              状态：
                              {ep.gridStatus === 'approved' && <span style={{ color: '#52c41a' }}>已通过</span>}
                              {ep.gridStatus === 'generated' && <span style={{ color: '#faad14' }}>待审核</span>}
                              {ep.gridStatus === 'rejected' && <span style={{ color: '#ff4d4f' }}>已退回</span>}
                              {ep.gridStatus === 'generating' && <span style={{ color: '#1677ff' }}>生成中</span>}
                              {ep.gridStatus === 'pending' && <span>待生成</span>}
                            </span>
                            {ep.gridRejectionFeedback && (
                              <div style={{ fontSize: 12, color: '#ff4d4f', marginTop: 4 }}>
                                退回原因：{ep.gridRejectionFeedback}
                              </div>
                            )}
                          </div>
                          <div style={{ display: 'flex', gap: 8 }}>
                            <button
                              className={styles.scriptGenerateBtn}
                              onClick={() => handleGenerateGrid(ep.episodeId)}
                              disabled={generatingGrid === ep.episodeId}
                            >
                              {generatingGrid === ep.episodeId ? '生成中...' : '生成九宫格'}
                            </button>
                            {(ep.gridStatus === 'generated' || ep.gridStatus === 'rejected') && (
                              <>
                                <button
                                  className={styles.scriptGridBtn}
                                  onClick={() => handleApproveGrid(ep.episodeId)}
                                >
                                  通过
                                </button>
                                <button
                                  className={styles.confirmButton}
                                  style={{ background: '#ef4440' }}
                                  onClick={() => {
                                    const reason = prompt('请输入退回原因:');
                                    if (reason) handleRejectGrid(ep.episodeId, reason);
                                  }}
                                >
                                  退回
                                </button>
                              </>
                            )}
                          </div>
                        </div>

                        {/* Grid images */}
                        {ep.gridImages && ep.gridImages.length > 0 && (
                          <div style={{
                            display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginTop: 12
                          }}>
                            {ep.gridImages.map((url, idx) => (
                              <img
                                key={idx}
                                src={url}
                                alt={`九宫格 ${idx + 1}`}
                                style={{ width: '100%', borderRadius: 6, aspectRatio: '1/1', objectFit: 'cover' }}
                              />
                            ))}
                          </div>
                        )}
                        {(!ep.gridImages || ep.gridImages.length === 0) && (
                          <div style={{
                            padding: '40px 0', textAlign: 'center', color: '#999', fontSize: 13
                          }}>
                            暂无九宫格图片
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              ))
            )}
          </div>
        )}

        {/* ==================== Tab 4c: Video ==================== */}
        {activeTab === 'video' && (
          <div>
            {chapters.length === 0 ? (
              <div className={styles.emptyState}><p>暂无章节数据</p></div>
            ) : (
              chapters.map(chapter => (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <div className={styles.chapterHeader}>
                    <span className={styles.chapterIcon}>📖</span>
                    <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
                  </div>
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => {
                      const allVideosDone = ep.segments.length > 0 &&
                        ep.segments.every(seg => seg.videoUrl || seg.pipelineStep === 'video_completed');
                      return (
                        <div key={ep.episodeId} style={{
                          padding: 16, marginBottom: 12, background: '#fff', borderRadius: 8,
                          border: '1px solid #f0f0f0'
                        }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
                            <div>
                              <h3 style={{ margin: 0, fontSize: 15, fontWeight: 600 }}>
                                第{ep.episodeIndex}集 {ep.title}
                              </h3>
                              <span style={{ fontSize: 13, color: '#999' }}>
                                {ep.segments.filter(s => s.videoUrl || s.pipelineStep === 'video_completed').length} / {ep.segments.length} 分镜已完成
                              </span>
                            </div>
                            <button
                              className={styles.scriptGridBtn}
                              onClick={() => handleBatchGenerateVideo(ep.episodeId)}
                              disabled={allVideosDone}
                            >
                              {allVideosDone ? '已完成' : '批量生成'}
                            </button>
                          </div>

                          {/* Panel video cards */}
                          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                            {ep.segments.map((seg, idx) => {
                              const panelId = seg.panelData?.panelId;
                              const isGenerating = generatingVideo === `${ep.episodeId}-${panelId}`;
                              const isDone = !!seg.videoUrl || seg.pipelineStep === 'video_completed';
                              return (
                                <div key={idx} style={{
                                  display: 'flex', alignItems: 'center', gap: 12, padding: 10,
                                  background: '#fafafa', borderRadius: 6
                                }}>
                                  <span style={{ fontSize: 13, fontWeight: 500, minWidth: 60 }}>
                                    {seg.title}
                                  </span>
                                  <span style={{ fontSize: 12, color: '#666', flex: 1 }}>
                                    {seg.synopsis}
                                  </span>
                                  {isDone ? (
                                    <span style={{ color: '#52c41a', fontSize: 13 }}>已完成</span>
                                  ) : (
                                    <button
                                      className={styles.scriptGenerateBtn}
                                      onClick={() => panelId && handleGenerateVideo(ep.episodeId, panelId)}
                                      disabled={!panelId || isGenerating}
                                    >
                                      {isGenerating ? '生成中...' : '生成视频'}
                                    </button>
                                  )}
                                  {seg.videoUrl && (
                                    <a
                                      href={seg.videoUrl}
                                      target="_blank"
                                      rel="noopener noreferrer"
                                      style={{ fontSize: 12, color: '#1677ff' }}
                                    >
                                      预览
                                    </a>
                                  )}
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))
            )}

            {/* Confirm all panels done */}
            {totalPanels > 0 && completedVideos === totalPanels && (
              <div className={styles.footerActions}>
                <button
                  className={styles.nextStepButton}
                  onClick={handleConfirmPanels}
                  disabled={advancing}
                >
                  {advancing ? '确认中...' : '确认完成，进入视频合成 →'}
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
