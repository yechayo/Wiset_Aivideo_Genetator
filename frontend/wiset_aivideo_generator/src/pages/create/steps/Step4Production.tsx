/**
 * Step4Production - 分镜生产 Tab 子阶段
 * Tab 4a: 脚本生成/审核
 * Tab 4b: 九宫格图片生成/审核
 * Tab 4c: 视频生成/确认
 */
import { useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import styles from './Step4Production.module.less';
import type { ChapterState, EpisodeState, SegmentState } from './types';
import {
  getEpisodes,
  getPanels,
  getBatchProductionStatuses,
  approvePanel,
  rejectPanel,
  approveEpisodeGrid,
  rejectEpisodeGrid,
  regenerateEpisodeGrid,
  generateVideo,
  generateEpisodeScripts,
  getVideoPrompt,
  enhanceVideoPrompt as enhanceVideoPromptApi,
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

const LockIcon = () => (
  <svg className={styles.stepLockIcon} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
    <path d="M7 11V7a5 5 0 0 1 10 0v4" />
  </svg>
);

const BookIcon = () => (
  <svg className={styles.chapterIcon} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
    <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
  </svg>
);

const CheckIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="20 6 9 17 4 12" />
  </svg>
);

const ArrowRightIcon = () => (
  <svg className={styles.btnCtaArrow} width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <line x1="5" y1="12" x2="19" y2="12" />
    <polyline points="12 5 19 12 12 19" />
  </svg>
);

const PlayIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polygon points="5 3 19 12 5 21 5 3" />
  </svg>
);

const SpinIcon = () => <span className={styles.btnSpinner} />;

const STEPS = [
  { key: 'script' as SubPhase, number: 'a', label: '脚本生成' },
  { key: 'grid' as SubPhase, number: 'b', label: '九宫格图片' },
  { key: 'video' as SubPhase, number: 'c', label: '视频生成' },
];

function getGridStatusBadge(status: string) {
  switch (status) {
    case 'approved': return { text: '已通过', className: styles.statusApproved };
    case 'generated': return { text: '待审核', className: styles.statusGenerated };
    case 'rejected': return { text: '已退回', className: styles.statusRejected };
    case 'generating': return { text: '生成中', className: styles.statusGenerating, pulse: true };
    default: return { text: '待生成', className: styles.statusPending };
  }
}

export default function Step4Production({ project, onNextStep }: Step4ProductionProps) {
  const projectId = project?.projectId;
  const navigate = useNavigate();
  const { statusInfo, syncStatus } = useCreateStore();

  // Tab state — persist to localStorage so refresh doesn't lose tab
  const [activeTab, setActiveTab] = useState<SubPhase>(() => {
    const saved = projectId && localStorage.getItem(`step4_tab_${projectId}`);
    return (saved === 'script' || saved === 'grid' || saved === 'video') ? saved : 'script';
  });
  const switchTab = useCallback((tab: SubPhase) => {
    setActiveTab(tab);
    if (projectId) localStorage.setItem(`step4_tab_${projectId}`, tab);
  }, [projectId]);

  // Data state
  const [chapters, setChapters] = useState<ChapterState[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // UI state
  const [expandedEpisodeId, setExpandedEpisodeId] = useState<number | null>(null);
  const [expandedPanelKey, setExpandedPanelKey] = useState<string | null>(null); // "episodeId-panelId"
  const [collapsedChapters, setCollapsedChapters] = useState<Set<number>>(new Set());
  // 提示词模态框
  const [promptModalPanelKey, setPromptModalPanelKey] = useState<string | null>(null);
  const [promptModalTab, setPromptModalTab] = useState<'view' | 'edit'>('view');
  const [promptText, setPromptText] = useState('');
  const [promptLoading, setPromptLoading] = useState(false);
  const [promptEnhancing, setPromptEnhancing] = useState(false);
  const [batchEnhancingEpisodeId, setBatchEnhancingEpisodeId] = useState<number | null>(null);
  const [generatingScript, setGeneratingScript] = useState<number | null>(null);
  const [lightboxUrl, setLightboxUrl] = useState<string | null>(null);
  const [generatingGrid, setGeneratingGrid] = useState<number | null>(null);
  const [generatingVideoKeys, setGeneratingVideoKeys] = useState<Set<string>>(new Set()); // "episodeId-panelId"
  const [approvingEpisodeId, setApprovingEpisodeId] = useState<number | null>(null);
  const [rejectingEpisodeId, setRejectingEpisodeId] = useState<number | null>(null);

  // ==================== Script Polling ====================
  const scriptPollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const startScriptPolling = useCallback(() => {
    if (scriptPollingRef.current) return;
    scriptPollingRef.current = setInterval(loadEpisodes, 5000);
  }, []); // eslint-disable-line-line react-hooks/exhaustive-deps

  const stopScriptPolling = useCallback(() => {
    if (scriptPollingRef.current) {
      clearInterval(scriptPollingRef.current);
      scriptPollingRef.current = null;
    }
  }, []);

  useEffect(() => {
    return () => stopScriptPolling();
  }, [stopScriptPolling]);

  useEffect(() => {
    if (statusInfo?.isGenerating) {
      startScriptPolling();
    } else {
      stopScriptPolling();
    }
  }, [statusInfo?.isGenerating, startScriptPolling, stopScriptPolling]);

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
              // Build dialogue string from API format: dialogue (string) + speaker (string) + dialogueTone
              let dialogueText = '';
              if (typeof shot.dialogue === 'string' && shot.dialogue && shot.dialogue !== '无') {
                const speakerPart = shot.speaker && shot.speaker !== '无' ? `${shot.speaker}` : '';
                const tonePart = shot.dialogueTone && shot.dialogueTone !== '无' ? `（${shot.dialogueTone}）` : '';
                dialogueText = speakerPart
                  ? `${speakerPart}${tonePart}：${shot.dialogue}`
                  : shot.dialogue;
              } else if (Array.isArray(shot.dialogue)) {
                dialogueText = shot.dialogue.map((d: any) => d.speaker ? `${d.speaker}：${d.text}` : d.text).join('\n');
              }

              // Build synopsis: prefer visualDescription, fallback to scene, then scene_summary
              const synopsis = shot.visualDescription || shot.visual_description
                || shot.scene_summary || shot.scene || '';

              textSegments.push({
                segmentIndex: sIdx,
                title: `分镜 ${sIdx + 1}`,
                synopsis,
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
                  composition: shot.shotSize || shot.composition || '',
                  shotType: shot.shot_type,
                  cameraAngle: shot.cameraAngle || shot.camera_angle,
                  cameraMovement: shot.cameraMovement || '',
                  pacing: shot.pacing,
                  dialogue: dialogueText,
                  scene: shot.scene || '',
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
            panelApproved: ep.episodeInfo?.panelApproved ?? false,
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

  // 恢复视频生成状态：刷新页面后如果后端还在生成，自动恢复 generatingVideoKeys
  useEffect(() => {
    if (chapters.length === 0) return;
    const keys = new Set<string>();
    for (const ch of chapters) {
      for (const ep of ch.episodes) {
        for (const seg of ep.segments) {
          if (seg.pipelineStep === 'video_generating') {
            const key = `${ep.episodeId}-${seg.panelData?.panelId}`;
            keys.add(key);
          }
        }
      }
    }
    if (keys.size > 0) setGeneratingVideoKeys(keys);
  }, [chapters]);

  // Auto-stop polling when all episodes have script data
  useEffect(() => {
    if (chapters.length === 0) return;
    const allHaveSegments = chapters.every(ch =>
      ch.episodes.every(ep => ep.segments.length > 0)
    );
    if (allHaveSegments) {
      stopScriptPolling();
    }
  }, [chapters, stopScriptPolling]);

  // Watch backend generating flag — if true and segments missing, restore polling
  useEffect(() => {
    if (statusInfo?.isGenerating) {
      startScriptPolling();
    } else {
      stopScriptPolling();
    }
  }, [statusInfo?.isGenerating, startScriptPolling, stopScriptPolling]);

  // Restore generating UI state from backend after page refresh
  useEffect(() => {
    if (!statusInfo?.isGenerating) {
      setGeneratingScript(null);
      setGeneratingGrid(null);
      setGeneratingVideoKeys(new Set());
      return;
    }
    const taskType = statusInfo.generatingTaskType;
    // "episode" = script generation in progress
    if (taskType === 'episode') {
      setGeneratingScript(-1); // -1 = batch generating, unknown specific episode
      // Switch to script tab
      switchTab('script');
    }
    // "panel" = grid/video generation in progress
    if (taskType === 'panel') {
      // Find episodes with generating panels
      const generatingEp = chapters.find(ch =>
        ch.episodes.some(ep => ep.segments.some(seg =>
          seg.pipelineStep === 'grid_generating' || seg.pipelineStep === 'video_generating'
        ))
      );
      if (generatingEp) {
        switchTab('grid');
      }
    }
  }, [statusInfo?.isGenerating, statusInfo?.generatingTaskType, chapters]);

  // ==================== Panel Loading ====================

  const loadPanelsForEpisode = useCallback(async (episodeId: number) => {
    if (!projectId || panelsLoadedRef.current.has(episodeId)) return;
    panelsLoadedRef.current.add(episodeId);
    try {
      const res = await getPanels(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const panels = res.data || [];

      // If no panels exist yet (script stage), don't overwrite shot-based segments
      if (panels.length === 0) return;

      const segments: SegmentState[] = panels.map((panel: any, idx: number) => {
        const info = panel.panelInfo || {};
        const shots = info.shots || [];
        const isGroupedPanel = shots.length > 1;
        const synopsis = isGroupedPanel
          ? shots.map((s: any) => {
              const speaker = s.speaker && s.speaker !== '无' ? `【${s.speaker}】` : '';
              const desc = s.visualDescription || s.visual_description || s.scene || '';
              return speaker ? `${speaker} ${desc}` : desc;
            }).filter(Boolean).join('\n')
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
            cameraMovement: info.camera_movement || '',
            scene: info.scene || '',
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
                    videoTaskId: status.videoTaskId ?? seg.videoTaskId,
                    videoOffPeak: status.offPeak ?? seg.videoOffPeak,
                    videoProgress: status.videoProgress ?? seg.videoProgress,
                    videoCredits: status.videoCredits ?? seg.videoCredits,
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
      // SSE event only contains metadata, must reload full episode data to get shots
      loadEpisodes();
      const ep = chapters.flatMap(ch => ch.episodes).find(e => e.episodeIndex === data.episodeNum);
      if (ep) {
        panelsLoadedRef.current.delete(ep.episodeId);
        loadPanelsForEpisode(ep.episodeId);
      }
    },
    onEpisodePanelDone: (data) => {
      loadEpisodes();
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
        // gridStatus 变为 generated/approved/failed 时全量刷新以获取 gridImages、splitShots 等
        loadEpisodes();
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

  // Tab 4b unlocked when ALL episodes have script approved (panelApproved)
  // Spec: "4a → 4b：所有集脚本确认后自动解锁"
  const tab4bUnlocked = allEpisodes.length > 0 && allEpisodes.every(ep => ep.panelApproved);

  // Tab 4c unlocked when ALL episodes have grid approved
  // Spec: "4b → 4c：所有集九宫格审核通过后自动解锁"
  const tab4cUnlocked = tab4bUnlocked && allEpisodes.every(ep => ep.gridStatus === 'approved');

  // ==================== Actions ====================

  // Generate script per episode
  const handleGenerateScript = useCallback(async (episodeId: number) => {
    if (!projectId || generatingScript) return;
    setGeneratingScript(episodeId);
    try {
      await generateEpisodeScripts(projectId, episodeId);
      // Start polling — SSE may miss the completion event
      startScriptPolling();
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.message || '生成脚本失败');
      stopScriptPolling();
      setGeneratingScript(null);
    }
  }, [projectId, generatingScript, loadEpisodes, startScriptPolling, stopScriptPolling]);

  // Approve/reject script per episode
  const handleApproveScript = useCallback(async (episodeId: number) => {
    if (!projectId || approvingEpisodeId) return;
    setApprovingEpisodeId(episodeId);
    try {
      await approvePanel(projectId, episodeId);
      await loadEpisodes();
      // 全部审核通过后自动切换到九宫格 Tab
      const res = await getEpisodes(projectId);
      const items = res.data?.items || [];
      if (items.length > 0 && items.every((ep: any) => ep.episodeInfo?.panelApproved)) {
        switchTab('grid');
      }
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '审核失败');
    } finally {
      setApprovingEpisodeId(null);
    }
  }, [projectId, loadEpisodes, approvingEpisodeId]);

  const handleRejectScript = useCallback(async (episodeId: number, reason: string) => {
    if (!projectId || rejectingEpisodeId) return;
    setRejectingEpisodeId(episodeId);
    try {
      await rejectPanel(projectId, episodeId, reason);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '退回失败');
    } finally {
      setRejectingEpisodeId(null);
    }
  }, [projectId, loadEpisodes, rejectingEpisodeId]);

  // Generate grid per episode
  const handleGenerateGrid = useCallback(async (episodeId: number) => {
    if (!projectId || generatingGrid) return;
    setGeneratingGrid(episodeId);
    try {
      await regenerateEpisodeGrid(projectId, episodeId);
      // 轮询等待九宫格生成完成（SSE 可能断连）
      const pollGrid = async () => {
        for (let i = 0; i < 60; i++) {
          await new Promise(r => setTimeout(r, 5000));
          const res = await getEpisodes(projectId);
          const ep = (res.data?.items || []).find((e: any) => e.id === episodeId);
          if (!ep) continue;
          const status = ep.episodeInfo?.gridStatus;
          if (status === 'generated' || status === 'approved') {
            loadEpisodes();
            return;
          }
          if (status === 'failed' || status === 'rejected') {
            loadEpisodes();
            return;
          }
        }
        // 超时后也刷新一次
        loadEpisodes();
      };
      pollGrid();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '生成九宫格失败');
    } finally {
      setGeneratingGrid(null);
    }
  }, [projectId, generatingGrid, loadEpisodes]);

  // Approve/reject grid per episode
  const handleApproveGrid = useCallback(async (episodeId: number) => {
    if (!projectId || approvingEpisodeId) return;
    setApprovingEpisodeId(episodeId);
    try {
      await approveEpisodeGrid(projectId, episodeId);
      panelsLoadedRef.current.delete(episodeId); // 后端已重建 Panel，强制重新加载
      await loadEpisodes();
      // 全部九宫格审核通过后自动切换到视频 Tab
      const res = await getEpisodes(projectId);
      const items = res.data?.items || [];
      if (items.length > 0 && items.every((ep: any) => ep.episodeInfo?.gridStatus === 'approved')) {
        switchTab('video');
      }
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '审核失败');
    } finally {
      setApprovingEpisodeId(null);
    }
  }, [projectId, loadEpisodes, approvingEpisodeId]);

  const handleRejectGrid = useCallback(async (episodeId: number, reason: string) => {
    if (!projectId || rejectingEpisodeId) return;
    setRejectingEpisodeId(episodeId);
    try {
      await rejectEpisodeGrid(projectId, episodeId, reason);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '退回失败');
    } finally {
      setRejectingEpisodeId(null);
    }
  }, [projectId, loadEpisodes, rejectingEpisodeId]);

  // Generate video per panel
  const handleGenerateVideo = useCallback(async (episodeId: number, panelId: string, customPrompt?: string) => {
    if (!projectId) return;
    const key = `${episodeId}-${panelId}`;
    setGeneratingVideoKeys(prev => new Set(prev).add(key));

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
            if (panelStatus.videoStatus === 'completed' || panelStatus.videoStatus === 'failed') {
              setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
              if (panelStatus.videoStatus === 'failed') alert('视频生成失败');
              return;
            }
          }
        } catch { /* continue polling */ }
      }
      if (retries >= VIDEO_POLL_MAX_RETRIES) console.warn('视频生成轮询超时');
      setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
    };
    poll();

    generateVideo(projectId, episodeId, Number(panelId), offPeak, customPrompt)
      .catch((err: any) => {
        abort.abort();
        alert(err?.response?.data?.message || err?.message || '生成视频失败');
        setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
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

  // 批量润色提示词
  const handleBatchEnhance = useCallback(async (episodeId: number) => {
    if (!projectId || batchEnhancingEpisodeId) return;
    const episode = chapters.flatMap(ch => ch.episodes).find(ep => ep.episodeId === episodeId);
    if (!episode) return;
    setBatchEnhancingEpisodeId(episodeId);
    let success = 0;
    let failed = 0;
    for (const seg of episode.segments) {
      const panelId = seg.panelData?.panelId;
      if (!panelId || seg.videoUrl) continue;
      try {
        await enhanceVideoPromptApi(projectId, episodeId, Number(panelId));
        success++;
      } catch {
        failed++;
      }
    }
    setBatchEnhancingEpisodeId(null);
    if (failed === 0) {
      alert(`已润色 ${success} 个分组的提示词`);
    } else {
      alert(`润色完成：${success} 成功，${failed} 失败`);
    }
  }, [projectId, chapters, batchEnhancingEpisodeId]);

  // Confirm all panels done -> advance to Step 5
  const [advancing, setAdvancing] = useState(false);
  const handleConfirmPanels = useCallback(async () => {
    if (!projectId || advancing) return;
    const confirmed = window.confirm('确认所有分镜视频无误后，将进入视频合成阶段。是否继续？');
    if (!confirmed) return;
    // 已完成的项目重新走 Step4 时，直接导航到 Step5，不调用状态推进（COMPLETED 状态无法再触发 confirm_panels）
    if (statusInfo?.statusCode === 'completed') {
      onNextStep?.() || navigate(`/project/${projectId}/step/5`, { replace: true });
      return;
    }
    setAdvancing(true);
    try {
      await advanceStatus(projectId, 'forward', 'confirm_panels');
      onNextStep?.();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '状态推进失败');
    } finally {
      setAdvancing(false);
    }
  }, [projectId, advancing, onNextStep, statusInfo?.statusCode]);

  // ==================== Render Helpers ====================

  const toggleChapter = useCallback((chapterIndex: number) => {
    setCollapsedChapters(prev => {
      const next = new Set(prev);
      if (next.has(chapterIndex)) next.delete(chapterIndex); else next.add(chapterIndex);
      return next;
    });
  }, []);

  const ChevronIcon = ({ open }: { open: boolean }) => (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
      style={{ transition: 'transform 0.2s', transform: open ? 'rotate(90deg)' : 'rotate(0deg)', flexShrink: 0 }}>
      <polyline points="9 18 15 12 9 6" />
    </svg>
  );

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
        </div>
      </div>

      {/* Step Progress Bar */}
      <div className={styles.stepProgress}>
        {STEPS.map((step, idx) => {
          const isActive = activeTab === step.key;
          const completed = step.key === 'script' ? tab4bUnlocked
            : step.key === 'grid' ? tab4cUnlocked
            : false;
          const locked = step.key === 'script' ? false
            : step.key === 'grid' ? !tab4bUnlocked
            : !tab4cUnlocked;
          const stepClasses = [
            styles.stepItem,
            isActive && styles.stepItemActive,
            completed && !isActive && styles.stepItemCompleted,
            locked && styles.stepItemLocked,
          ].filter(Boolean).join(' ');

          return (
            <div key={step.key} style={{ display: 'contents' }}>
              <button
                className={stepClasses}
                onClick={() => !locked && switchTab(step.key)}
                disabled={locked}
              >
                <span className={styles.stepNumber}>
                  {completed && !isActive ? <CheckIcon /> : `4${step.number}`}
                </span>
                <span className={styles.stepLabel}>{step.label}</span>
                {locked && <LockIcon />}
              </button>
              {idx < STEPS.length - 1 && (
                <div className={`${styles.stepConnector} ${completed ? styles.stepConnectorCompleted : ''}`} />
              )}
            </div>
          );
        })}
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
              chapters.map(chapter => {
                const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
                return (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <button className={styles.chapterHeader} onClick={() => toggleChapter(chapter.chapterIndex)}>
                    <ChevronIcon open={chapterOpen} />
                    <BookIcon />
                    <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
                  </button>
                  {chapterOpen && (
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => (
                      <div key={ep.episodeId} className={`${styles.episodeScriptCard} ${(generatingScript === ep.episodeId || generatingScript === -1) ? styles.cardGenerating : ''}`}>
                        {(generatingScript === ep.episodeId || generatingScript === -1) && (
                          <div className={styles.cardLoadingOverlay}>
                            <div className={styles.cardLoadingSpinner} />
                            <span>脚本生成中...</span>
                          </div>
                        )}
                        <div className={styles.episodeScriptHeader}>
                          <div>
                            <h3 className={styles.episodeScriptTitle}>
                              第{ep.episodeIndex}集 {ep.title}
                            </h3>
                            <span className={styles.episodeScriptCount}>
                              {ep.segments.length > 0 ? `${ep.segments.length} 个分镜` : '暂无分镜数据'}
                            </span>
                          </div>
                          <div className={styles.episodeScriptActions}>
                            <button
                              className={styles.btnPrimary}
                              onClick={() => handleGenerateScript(ep.episodeId)}
                              disabled={generatingScript === ep.episodeId || generatingScript === -1}
                            >
                              {(generatingScript === ep.episodeId || generatingScript === -1) ? <><SpinIcon /> 生成中...</> : '生成脚本'}
                            </button>
                            {ep.segments.length > 0 && (
                              <>
                                <button
                                  className={styles.btnSuccess}
                                  onClick={() => handleApproveScript(ep.episodeId)}
                                  disabled={approvingEpisodeId === ep.episodeId || rejectingEpisodeId === ep.episodeId}
                                >
                                  {approvingEpisodeId === ep.episodeId ? <><SpinIcon /> 审核中...</> : '通过'}
                                </button>
                                <button
                                  className={styles.btnDanger}
                                  onClick={() => {
                                    const reason = prompt('请给出你的优化建议:');
                                    if (reason) handleRejectScript(ep.episodeId, reason!);
                                  }}
                                  disabled={approvingEpisodeId === ep.episodeId || rejectingEpisodeId === ep.episodeId}
                                >
                                  {rejectingEpisodeId === ep.episodeId ? <><SpinIcon /> 退回中...</> : '退回'}
                                </button>
                              </>
                            )}
                          </div>
                        </div>

                        {/* Expand to show script text */}
                        <button
                          className={styles.expandToggle}
                          onClick={() => toggleEpisode(ep.episodeId)}
                        >
                          {expandedEpisodeId === ep.episodeId ? '收起' : '展开'}分镜文本 &#9660;
                        </button>

                        {expandedEpisodeId === ep.episodeId && ep.segments.length > 0 && (
                          <div className={styles.scriptSegmentList}>
                            {ep.segments.map((seg, idx) => (
                              <div key={idx} className={styles.scriptSegmentItem}>
                                <div className={styles.scriptSegmentTitle}>分镜 {idx + 1}</div>
                                {seg.synopsis && (
                                  <div className={styles.scriptSegmentDetail}>
                                    <span>画面：</span>{seg.synopsis}
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
                                {seg.panelData?.composition && (
                                  <div className={styles.scriptSegmentDetail}>
                                    <span>镜头：</span>{seg.panelData.composition}
                                    {seg.panelData?.cameraAngle && ` / ${seg.panelData.cameraAngle}`}
                                    {seg.panelData?.cameraMovement && ` / ${seg.panelData.cameraMovement}`}
                                  </div>
                                )}
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                  )}
                </div>
              )
              })
            )}
          </div>
        )}

        {/* ==================== Tab 4b: Grid ==================== */}
        {activeTab === 'grid' && (
          <div className={styles.gridReviewSection}>
            {chapters.length === 0 ? (
              <div className={styles.emptyState}><p>暂无章节数据</p></div>
            ) : (
              chapters.map(chapter => {
                const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
                return (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <button className={styles.chapterHeader} onClick={() => toggleChapter(chapter.chapterIndex)}>
                    <ChevronIcon open={chapterOpen} />
                    <BookIcon />
                    <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
                  </button>
                  {chapterOpen && (
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => {
                      const badge = getGridStatusBadge(ep.gridStatus || 'pending');
                      return (
                        <div key={ep.episodeId} className={styles.episodeGridCard}>
                          <div className={styles.episodeGridHeader}>
                            <div>
                              <h3 className={styles.episodeGridTitle}>
                                第{ep.episodeIndex}集 {ep.title}
                              </h3>
                              <span className={`${styles.statusBadge} ${badge.className}`}>
                                <span className={`${styles.statusDot} ${badge.pulse ? styles.statusDotPulse : ''}`} />
                                {badge.text}
                              </span>
                              {ep.gridRejectionFeedback && (
                                <div className={styles.gridRejectionFeedback}>
                                  退回原因：{ep.gridRejectionFeedback}
                                </div>
                              )}
                            </div>
                            <div className={styles.episodeGridActions}>
                              {ep.gridStatus !== 'approved' && ep.gridStatus !== 'generated' && (
                              <button
                                className={styles.btnPrimary}
                                onClick={() => handleGenerateGrid(ep.episodeId)}
                                disabled={generatingGrid === ep.episodeId}
                              >
                                {generatingGrid === ep.episodeId ? <><SpinIcon /> 生成中...</> : '生成九宫格'}
                              </button>
                              )}
                              {(ep.gridStatus === 'generated' || ep.gridStatus === 'rejected') && (
                                <>
                                  <button
                                    className={styles.btnSuccess}
                                    onClick={() => handleApproveGrid(ep.episodeId)}
                                    disabled={approvingEpisodeId === ep.episodeId || rejectingEpisodeId === ep.episodeId}
                                  >
                                    {approvingEpisodeId === ep.episodeId ? <><SpinIcon /> 审核中...</> : '通过'}
                                  </button>
                                  <button
                                    className={styles.btnDanger}
                                    onClick={() => {
                                      const reason = prompt('请给出你的优化建议:');
                                      if (reason) handleRejectGrid(ep.episodeId, reason!);
                                    }}
                                    disabled={approvingEpisodeId === ep.episodeId || rejectingEpisodeId === ep.episodeId}
                                  >
                                    {rejectingEpisodeId === ep.episodeId ? <><SpinIcon /> 退回中...</> : '退回'}
                                  </button>
                                </>
                              )}
                            </div>
                          </div>

                          {/* Grid images */}
                          {ep.gridImages && ep.gridImages.length > 0 && (
                            <div className={styles.gridImagesContainer}>
                              {ep.gridImages.map((url, idx) => (
                                <img
                                  key={idx}
                                  src={url}
                                  alt={`九宫格 ${idx + 1}`}
                                  className={styles.gridImage}
                                  onClick={() => setLightboxUrl(url)}
                                />
                              ))}
                            </div>
                          )}
                          {(!ep.gridImages || ep.gridImages.length === 0) && (
                            <div className={styles.gridEmptyState}>
                              暂无九宫格图片
                            </div>
                          )}
                        </div>
                      )
                    })}
                  </div>
                  )}
                </div>
              )
              })
            )}
          </div>
        )}

        {/* ==================== Tab 4c: Video ==================== */}
        {activeTab === 'video' && (
          <div className={styles.videoReviewSection}>
            {chapters.length === 0 ? (
              <div className={styles.emptyState}><p>暂无章节数据</p></div>
            ) : (
              chapters.map(chapter => {
                const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
                return (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <button className={styles.chapterHeader} onClick={() => toggleChapter(chapter.chapterIndex)}>
                    <ChevronIcon open={chapterOpen} />
                    <BookIcon />
                    <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
                  </button>
                  {chapterOpen && (
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => {
                      const doneCount = ep.segments.filter(s => s.videoUrl || s.pipelineStep === 'video_completed').length;
                      const allDone = ep.segments.length > 0 && doneCount === ep.segments.length;
                      return (
                        <div key={ep.episodeId} className={styles.episodeVideoCard}>
                          <div className={styles.episodeVideoHeader}>
                            <div>
                              <h3 className={styles.episodeVideoTitle}>
                                第{ep.episodeIndex}集 {ep.title}
                              </h3>
                              <span className={styles.episodeVideoProgress}>
                                {doneCount} / {ep.segments.length} 分镜已完成
                              </span>
                            </div>
                            <div className={styles.episodeVideoHeaderActions}>
                              <button
                                className={allDone ? styles.btnGhost : styles.btnSuccess}
                                onClick={() => handleBatchGenerateVideo(ep.episodeId)}
                                disabled={allDone}
                              >
                                {allDone ? '已完成' : '批量生成'}
                              </button>
                              {!allDone && (
                                <button
                                  className={styles.btnGhost}
                                  onClick={() => handleBatchEnhance(ep.episodeId)}
                                  disabled={!!batchEnhancingEpisodeId}
                                >
                                  {batchEnhancingEpisodeId === ep.episodeId ? <><SpinIcon /> 润色中...</> : '批量润色'}
                                </button>
                              )}
                            </div>
                          </div>

                          {/* Panel video rows */}
                          <div className={styles.panelVideoList}>
                            {ep.segments.map((seg, idx) => {
                              const panelId = seg.panelData?.panelId;
                              const isGenerating = generatingVideoKeys.has(`${ep.episodeId}-${panelId}`);
                              const isFailed = seg.pipelineStep === 'video_failed';
                              const isDone = !!seg.videoUrl || seg.pipelineStep === 'video_completed';
                              const panelKey = `${ep.episodeId}-${panelId}`;
                              const isExpanded = expandedPanelKey === panelKey;
                              const shotDescriptions = (seg.shots || [])
                                .map((s: any) => s.visualDescription || s.visual_description || s.scene || '')
                                .filter(Boolean);
                              return (
                                <div key={idx} className={styles.panelVideoCard}>
                                  <div className={styles.panelVideoRow}>
                                    <span className={styles.panelVideoNumber}>
                                      {seg.title}
                                    </span>
                                    <span className={styles.panelVideoSynopsis}>
                                      {seg.synopsis}
                                    </span>
                                    {isDone ? (
                                      <span className={styles.panelVideoStatus}>已完成</span>
                                    ) : isGenerating && !isFailed ? (
                                      <span className={styles.panelVideoGenerating}>
                                        <SpinIcon /> {seg.videoProgress != null ? `${seg.videoProgress}%` : '生成中...'}
                                      </span>
                                    ) : isFailed ? (
                                      <span className={styles.panelVideoFailed}>
                                        生成失败
                                        <button
                                          className={styles.btnPrimary}
                                          style={{ marginLeft: 8 }}
                                          onClick={() => panelId && handleGenerateVideo(ep.episodeId, panelId)}
                                          disabled={!panelId}
                                        >
                                          重试
                                        </button>
                                      </span>
                                    ) : (
                                      <button
                                        className={styles.btnPrimary}
                                        onClick={() => panelId && handleGenerateVideo(ep.episodeId, panelId)}
                                        disabled={!panelId}
                                      >
                                        生成视频
                                      </button>
                                    )}
                                    {seg.videoUrl && (
                                      <button
                                        className={styles.panelVideoPreview}
                                        onClick={() => setExpandedPanelKey(isExpanded ? null : panelKey)}
                                      >
                                        {isExpanded ? '收起' : <><PlayIcon /> 预览</>}
                                      </button>
                                    )}
                                    {/* 提示词按钮 */}
                                    {panelId && (
                                      <button
                                        className={styles.btnGhost}
                                        onClick={() => {
                                          setPromptModalPanelKey(panelKey);
                                          setPromptModalTab('view');
                                          setPromptText('');
                                          setPromptLoading(true);
                                          getVideoPrompt(projectId!, ep.episodeId, Number(panelId))
                                            .then(res => setPromptText(res.data?.prompt || ''))
                                            .catch(() => setPromptText(''))
                                            .finally(() => setPromptLoading(false));
                                        }}
                                      >
                                        提示词
                                      </button>
                                    )}
                                    {/* 展开详情按钮 */}
                                    <button
                                      className={styles.panelExpandBtn}
                                      onClick={() => setExpandedPanelKey(isExpanded ? null : panelKey)}
                                    >
                                      {isExpanded ? '收起' : '详情'} &#9660;
                                    </button>
                                  </div>
                                  {/* 视频播放器 */}
                                  {isExpanded && seg.videoUrl && (
                                    <div className={styles.panelVideoPlayerWrap}>
                                      <video
                                        key={seg.videoUrl}
                                        className={styles.panelVideoPlayer}
                                        controls
                                        autoPlay
                                        src={seg.videoUrl!}
                                      />
                                    </div>
                                  )}
                                  {/* 进度条 */}
                                  {isGenerating && (
                                    <div className={styles.panelVideoProgressBar}>
                                      <div
                                        className={styles.panelVideoProgressFill}
                                        style={{ width: `${seg.videoProgress ?? 0}%` }}
                                      />
                                    </div>
                                  )}
                                  {/* 元信息：积分、任务ID、错峰 */}
                                  {(seg.videoCredits != null || seg.videoTaskId || seg.videoOffPeak) && (
                                    <div className={styles.panelVideoMeta}>
                                      {seg.videoOffPeak && <span className={styles.panelVideoTag}>错峰模式</span>}
                                      {seg.videoCredits != null && <span className={styles.panelVideoTag}>{seg.videoCredits} 积分</span>}
                                      {seg.videoTaskId && <span className={styles.panelVideoTag}>{seg.videoTaskId.slice(0, 8)}...</span>}
                                    </div>
                                  )}
                                  {isExpanded && (
                                    <div className={styles.panelDetailContent}>
                                      {seg.fusionImageUrl && (
                                        <div className={styles.panelDetailSection}>
                                          <span className={styles.panelDetailLabel}>融合参考图</span>
                                          <img src={seg.fusionImageUrl} alt="融合参考图" className={styles.panelFusionImage} />
                                        </div>
                                      )}
                                      {shotDescriptions.length > 0 && (
                                        <div className={styles.panelDetailSection}>
                                          <span className={styles.panelDetailLabel}>分镜描述</span>
                                          <div className={styles.panelPromptList}>
                                            {shotDescriptions.map((desc: string, sIdx: number) => (
                                              <div key={sIdx} className={styles.panelPromptItem}>
                                                <span className={styles.panelPromptIndex}>{sIdx + 1}</span>
                                                <span className={styles.panelPromptText}>{desc}</span>
                                              </div>
                                            ))}
                                          </div>
                                        </div>
                                      )}
                                    </div>
                                  )}
                                </div>
                              );
                            })}
                          </div>
                        </div>
                      )
                    })}
                  </div>
                  )}
                </div>
              )
              })
            )}
          </div>
        )}

        {/* Confirm all panels done - outside scroll area */}
        {activeTab === 'video' && totalPanels > 0 && completedVideos === totalPanels && (
          <div className={styles.footerActions}>
            <button
              className={styles.btnCta}
              onClick={handleConfirmPanels}
              disabled={advancing}
            >
              {advancing ? '确认中...' : '确认完成，进入视频合成'}
              <ArrowRightIcon />
            </button>
          </div>
        )}

        {/* 提示词模态框 */}
        {promptModalPanelKey && (() => {
          const [episodeIdStr, panelIdStr] = promptModalPanelKey.split('-');
          const episodeId = Number(episodeIdStr);
          const panelId = panelIdStr;
          const ep = chapters.flatMap(ch => ch.episodes).find(e => e.episodeId === episodeId);
          const seg = ep?.segments.find(s => s.panelData?.panelId === panelId);

          const handleEnhance = async () => {
            setPromptEnhancing(true);
            try {
              const res = await enhanceVideoPromptApi(projectId!, episodeId, Number(panelId));
              setPromptText(res.data?.prompt || '');
              setPromptModalTab('edit');
            } catch (err: any) {
              alert(err?.response?.data?.message || err?.message || '提示词优化失败');
            }
            setPromptEnhancing(false);
          };

          const handleSubmitPrompt = () => {
            if (!panelId) return;
            setPromptModalPanelKey(null);
            handleGenerateVideo(episodeId, panelId, promptModalTab === 'edit' ? promptText : undefined);
          };

          return (
            <div className={styles.modalOverlay} onClick={() => setPromptModalPanelKey(null)}>
              <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
                <div className={styles.modalHeader}>
                  <h3>{promptModalTab === 'edit' ? '编辑提示词' : '查看提示词'}</h3>
                  <button className={styles.modalClose} onClick={() => setPromptModalPanelKey(null)}>&times;</button>
                </div>

                {/* 融合参考图 */}
                {seg?.fusionImageUrl && (
                  <div className={styles.modalFusionWrap}>
                    <span className={styles.modalFusionLabel}>融合参考图</span>
                    <img src={seg.fusionImageUrl} alt="融合参考图" className={styles.modalFusionImg} />
                  </div>
                )}

                {/* Tab 切换 */}
                <div className={styles.modalTabBar}>
                  <button
                    className={`${styles.modalTab} ${promptModalTab === 'view' ? styles.modalTabActive : ''}`}
                    onClick={() => setPromptModalTab('view')}
                  >仅查看</button>
                  <button
                    className={`${styles.modalTab} ${promptModalTab === 'edit' ? styles.modalTabActive : ''}`}
                    onClick={() => setPromptModalTab('edit')}
                  >编辑后生成</button>
                </div>

                {/* 提示词内容 */}
                <div className={styles.modalPromptSection}>
                  {promptLoading ? (
                    <div className={styles.modalLoading}>加载中...</div>
                  ) : promptModalTab === 'view' ? (
                    <div className={styles.modalPromptView}>
                      <div className={styles.modalPromptHint}>以下是 AI 根据分镜内容自动生成的提示词，用于视频生成</div>
                      <pre className={styles.modalPromptPre}>{promptText || '(暂无提示词)'}</pre>
                      <button
                        className={styles.modalEnhanceBtn}
                        onClick={handleEnhance}
                        disabled={promptEnhancing || promptLoading}
                      >
                        {promptEnhancing ? '优化中...' : 'AI 优化提示词（消耗1积分）'}
                      </button>
                    </div>
                  ) : (
                    <div className={styles.modalPromptEdit}>
                      <div className={styles.modalPromptHint}>修改提示词可以更好地控制视频生成效果。修改后的提示词会用于生成。</div>
                      <textarea
                        className={styles.modalTextarea}
                        value={promptText}
                        onChange={e => setPromptText(e.target.value)}
                        placeholder="输入自定义提示词..."
                        rows={12}
                      />
                    </div>
                  )}
                </div>

                {/* 操作按钮 */}
                <div className={styles.modalActions}>
                  <button className={styles.btnGhost} onClick={() => setPromptModalPanelKey(null)}>取消</button>
                  <button
                    className={styles.btnPrimary}
                    onClick={handleSubmitPrompt}
                    disabled={promptLoading || (promptModalTab === 'edit' && !promptText.trim())}
                  >
                    {promptModalTab === 'edit' ? '使用修改后的提示词生成' : '使用原提示词生成'}
                  </button>
                </div>
              </div>
            </div>
          );
        })()}

        {/* Lightbox */}
        {lightboxUrl && (
          <div className={styles.lightboxOverlay} onClick={() => setLightboxUrl(null)}>
            <img className={styles.lightboxImg} src={lightboxUrl} alt="九宫格大图" onClick={e => e.stopPropagation()} />
            <button className={styles.lightboxClose} onClick={() => setLightboxUrl(null)}>&times;</button>
          </div>
        )}
      </div>
    </div>
  );
}
