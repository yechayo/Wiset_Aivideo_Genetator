import { useState, useCallback, useEffect, useRef } from 'react';
import styles from './Step5page.module.less';
import type {
  ChapterState,
  EpisodeState,
  SegmentState,
  ExpansionState,
  SegmentPipelineStep,
} from './types';
import {
  getEpisodes,
  getPanels,
  getBatchProductionStatuses,
  approveGrid,
  rejectGrid,
  regenerateGrid,
  generateVideo,
  getEpisodeGridStatus,
  approveEpisodeGrid,
  rejectEpisodeGrid,
  regenerateEpisodeGrid,
  getVideoPrompt,
} from '../../../services/episodeService';
import { getCharacterStatus, getCharacters } from '../../../services/characterService';
import { advanceStatus } from '../../../services/projectService';
import { useCreateStore } from '../../../stores/createStore';
import EpisodeCard from './components/EpisodeCard';
import { BatchReviewBar } from './components/BatchReviewBar';

/** 轮询配置 */
const GRID_POLL_INTERVAL = 5000;
const GRID_POLL_MAX_RETRIES = 360;   // 30 分钟
const VIDEO_POLL_INTERVAL = 3000;
const VIDEO_POLL_MAX_RETRIES = 720;  // 1 小时

interface Step5pageProps {
  project: any;
  onNextStep?: () => void;
}

/**
 * 将后端 PanelGridStatusResponse 映射为前端 SegmentPipelineStep
 */
function mapGridToPipelineStep(status: {
  gridStatus: string;
  videoStatus: string;
}): SegmentPipelineStep {
  if (status.videoStatus === 'completed') return 'video_completed';
  if (status.videoStatus === 'failed') return 'video_failed';
  if (status.videoStatus === 'generating') return 'video_generating';
  if (status.gridStatus === 'approved') return 'grid_approved';
  if (status.gridStatus === 'generating') return 'grid_generating';
  if (status.gridStatus === 'generated') return 'grid_review';
  if (status.gridStatus === 'failed') return 'grid_review';  // still show for error state
  if (status.gridStatus === 'rejected') return 'grid_review';
  return 'pending';
}

/**
 * Step5page: 视频生产工作台
 *
 * 展示三级可展开列表（章节 → 集数 → 片段），
 * 顶部显示完成进度统计栏。
 */
const Step5page = ({ project, onNextStep }: Step5pageProps) => {
  const projectId = project?.projectId;
  const { statusInfo, syncStatus } = useCreateStore();

  // 检测分集剧本/分镜生成失败状态
  const failedStatusCode = statusInfo?.isFailed && statusInfo?.statusCode
    ? statusInfo.statusCode : '';
  const isPipelineFailed = failedStatusCode === 'EPISODE_SCRIPT_GENERATING_FAILED'
    || failedStatusCode === 'STORYBOARD_GENERATING_FAILED';
  const [retrying, setRetrying] = useState(false);

  const handleRetryPipeline = useCallback(async () => {
    if (!projectId || retrying) return;
    setRetrying(true);
    try {
      await advanceStatus(projectId, 'forward', 'retry');
      await syncStatus(projectId);
    } catch (e: any) {
      setError(e.message || '重试失败');
    } finally {
      setRetrying(false);
    }
  }, [projectId, retrying, syncStatus]);

  // 下一步：推进后端状态后跳转
  const [advancing, setAdvancing] = useState(false);
  const handleNextStep = useCallback(async () => {
    if (!projectId || advancing) return;
    setAdvancing(true);
    try {
      await advanceStatus(projectId, 'forward', 'production_completed');
      onNextStep?.();
    } catch (e: any) {
      setError(e.message || '状态推进失败');
    } finally {
      setAdvancing(false);
    }
  }, [projectId, advancing, onNextStep]);

  // 数据状态
  const [chapters, setChapters] = useState<ChapterState[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [charAvatarMap, setCharAvatarMap] = useState<Record<string, string>>({});
  // 角色 name→ID 映射，用于修正 AI 把名字填进 char_id 的老数据
  const [charNameToIdMap, setCharNameToIdMap] = useState<Record<string, string>>({});
  const [generatingGridPanelId, setGeneratingGridPanelId] = useState<string | null>(null);
  const [generatingVideoPanelId, setGeneratingVideoPanelId] = useState<string | null>(null);

  // UI 状态：当前展开的集数/片段（手风琴模式）
  const [expansion, setExpansion] = useState<ExpansionState>({
    expandedEpisodeId: null,
    expandedSegmentKey: null,
  });

  // 错峰模式开关（localStorage 持久化）
  const [offPeak, setOffPeak] = useState(() => {
    const saved = localStorage.getItem('video_off_peak');
    return saved === 'true';
  });
  const toggleOffPeak = useCallback(() => {
    setOffPeak(prev => {
      const next = !prev;
      localStorage.setItem('video_off_peak', String(next));
      return next;
    });
  }, []);

  /**
   * 加载剧集列表
   */
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

      // 按章节分组
      const chapterMap = new Map<string, any[]>();
      items.forEach(ep => {
        const chapterTitle = ep.episodeInfo?.chapterTitle?.trim() || '未分章';
        if (!chapterMap.has(chapterTitle)) {
          chapterMap.set(chapterTitle, []);
        }
        chapterMap.get(chapterTitle)!.push(ep);
      });

      const builtChapters: ChapterState[] = [];
      let chapterIndex = 0;
      for (const [chapterTitle, chapterEpisodes] of chapterMap.entries()) {
        chapterIndex++;
        const episodeStates: EpisodeState[] = chapterEpisodes.map((ep, idx) => {
          // 从 panelPlan 解析 scene_summary 映射
          let sceneSummaryMap: Record<string, string> = {};
          try {
            const planStr = ep.episodeInfo?.panelPlan;
            if (planStr) {
              const plan = JSON.parse(planStr);
              if (Array.isArray(plan?.panels)) {
                sceneSummaryMap = {};
                plan.panels.forEach((p: any) => {
                  if (p.panel_id && p.scene_summary) {
                    sceneSummaryMap[p.panel_id] = p.scene_summary;
                  }
                });
              }
            }
          } catch { /* ignore parse error */ }

          return {
            episodeId: ep.id,
            episodeIndex: ep.episodeInfo?.episodeNum || idx + 1,
            title: ep.episodeInfo?.title,
            sceneSummaryMap,
            segments: [],
            // 新流程：提取 Episode 级九宫格数据
            gridStatus: ep.episodeInfo?.gridStatus || undefined,
            gridImages: ep.episodeInfo?.gridImages || [],
            splitShots: ep.episodeInfo?.splitShots || [],
            gridRejectionFeedback: ep.episodeInfo?.gridRejectionFeedback || null,
            isNewFlow: !!ep.episodeInfo?.gridStatus,
          };
        });
        builtChapters.push({
          chapterIndex,
          title: chapterTitle,
          episodes: episodeStates,
        });
      }
      setChapters(builtChapters);

      // 自动加载所有剧集的 panels 和生产状态
      const allIds = builtChapters.flatMap(ch => ch.episodes.map(ep => ep.episodeId));
      allIds.forEach(eid => {
        loadPanelsForEpisode(eid);
        refreshProductionStatuses(eid);
      });
    } catch (err: any) {
      setError(err?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadEpisodes();
  }, [loadEpisodes]);

  /**
   * 页面加载恢复：检测正在生成九宫格的 episode，自动恢复轮询
   */
  useEffect(() => {
    if (chapters.length === 0) return;
    const allEpisodes = chapters.flatMap(ch => ch.episodes);
    const generatingGridEp = allEpisodes.find(ep =>
      ep.isNewFlow && ep.gridStatus === 'generating'
    );
    if (!generatingGridEp) return;

    const gridEpId = generatingGridEp.episodeId;
    console.info('检测到正在生成中的 episode 九宫格, 恢复轮询: epId=', gridEpId);
    refreshEpisodeGridStatus(gridEpId);
    const abort = new AbortController();
    let retries = 0;
    const gridPoll = async () => {
      while (retries < GRID_POLL_MAX_RETRIES && !abort.signal.aborted) {
        await new Promise(r => setTimeout(r, GRID_POLL_INTERVAL));
        if (abort.signal.aborted) return;
        retries++;
        try {
          await refreshEpisodeGridStatus(gridEpId);
          const currentEps = chapters.flatMap(ch => ch.episodes);
          const currentGridEp = currentEps.find(e => e.episodeId === gridEpId);
          if (currentGridEp && (currentGridEp.gridStatus === 'generated' || currentGridEp.gridStatus === 'approved' || currentGridEp.gridStatus === 'failed')) {
            return;
          }
        } catch {
          // 继续轮询
        }
      }
      if (retries >= GRID_POLL_MAX_RETRIES) {
        console.warn('九宫格轮询超时: epId=', gridEpId);
      }
    };
    gridPoll();
    return () => { abort.abort(); };
  }, [chapters.length]); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * 加载项目角色列表，构建 name→ID 映射
   * 用于修正老数据中 AI 把角色名字填进 char_id 的问题
   */
  useEffect(() => {
    if (!projectId) return;
    (async () => {
      try {
        const res = await getCharacters(projectId, { page: 1, size: 100 });
        const items = res.data?.items || [];
        const map: Record<string, string> = {};
        items.forEach((c: any) => {
          if (c.charId && c.name) {
            map[c.name] = c.charId;
          }
        });
        if (Object.keys(map).length > 0) {
          setCharNameToIdMap(map);
        }
      } catch {
        // 静默失败
      }
    })();
  }, [projectId]);

  // 当 charNameToIdMap 就绪后，重新加载已展开集的分镜（此时 map 已有数据，可正确修正 char_id）
  useEffect(() => {
    if (Object.keys(charNameToIdMap).length === 0) return;
    // 已有 panels 缓存，清除后让 loadPanelsForEpisode 重新走一遍（map 已生效）
    const loadedEpisodes = panelsLoadedRef.current;
    if (loadedEpisodes.size === 0) return;
    // 短暂延迟确保 loadPanelsForEpisode 的 useCallback 已用新 map 重建
    const timer = setTimeout(() => {
      const episodeIds = [...loadedEpisodes];
      loadedEpisodes.clear();
      episodeIds.forEach(eid => loadPanelsForEpisode(eid));
    }, 0);
    return () => clearTimeout(timer);
  }, [charNameToIdMap]); // eslint-disable-line react-hooks/exhaustive-deps

  // 计算完成统计（兼容新旧流程）
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

  // 统计 episode 级九宫格
  const newFlowEpisodes = chapters.flatMap(ch => ch.episodes).filter(ep => ep.isNewFlow);
  const approvedGridEpisodes = newFlowEpisodes.filter(ep => ep.gridStatus === 'approved').length;
  const pendingGridEpisodes = newFlowEpisodes.filter(ep => ep.gridStatus === 'generated' || ep.gridStatus === 'rejected').length;

  // 记录已加载过分镜的集数，避免重复请求
  const panelsLoadedRef = useRef<Set<number>>(new Set());

  /**
   * 加载分镜中涉及的角色三视图
   */
  const loadCharacterAvatars = useCallback(async (charIds: string[]) => {
    const missing = charIds.filter(id => id && !charAvatarMap[id]);
    if (missing.length === 0 || !projectId) return;
    try {
      const results = await Promise.allSettled(
        missing.map(charId => getCharacterStatus(projectId, charId))
      );
      const newMap: Record<string, string> = {};
      results.forEach((r, i) => {
        if (r.status === 'fulfilled' && r.value?.data) {
          const d = r.value.data;
          if (d.threeViewGridUrl) {
            newMap[missing[i]] = d.threeViewGridUrl;
          } else if (d.expressionGridUrl) {
            newMap[missing[i]] = d.expressionGridUrl;
          }
        }
      });
      if (Object.keys(newMap).length > 0) {
        setCharAvatarMap(prev => ({ ...prev, ...newMap }));
      }
    } catch {
      // 静默失败
    }
  }, [projectId, charAvatarMap]);

  /**
   * 加载某集的分镜列表
   */
  const loadPanelsForEpisode = useCallback(async (episodeId: number) => {
    if (!projectId || panelsLoadedRef.current.has(episodeId)) return;
    panelsLoadedRef.current.add(episodeId);
    let charIds: string[] = [];
    try {
      const res = await getPanels(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const panels = res.data || [];

      // 收集涉及的角色 ID（兼容新旧流程）
      charIds = [...new Set(
        panels.flatMap((p: any) => {
          const info = p.panelInfo || {};
          // 旧流程：panelInfo.characters[].char_id
          const oldChars = (info.characters || []).map((c: any) => {
            const raw = c.char_id || '';
            if (raw && !raw.startsWith('CHAR-') && charNameToIdMap[raw]) {
              return charNameToIdMap[raw];
            }
            return raw.startsWith('CHAR-') ? raw : '';
          }).filter(Boolean);
          // 新流程：shots[].characterRefs[].charId
          const newChars = ((info.shots || []) as any[]).flatMap((s: any) =>
            ((s.characterRefs || []) as any[]).map((ref: any) => ref.charId || '').filter(Boolean)
          );
          return [...oldChars, ...newChars];
        })
      )];

      // 获取对应 episode 的 sceneSummaryMap，用于后备获取剧情摘要
      const currentEpisode = await new Promise<EpisodeState | undefined>(resolve => {
        setChapters(prev => {
          const ep = prev.flatMap(ch => ch.episodes).find(e => e.episodeId === episodeId);
          resolve(ep);
          return prev;
        });
      });
      const sceneSummaryMap = currentEpisode?.sceneSummaryMap || {};

      // 将分镜转为 segments
      const segments: SegmentState[] = panels.map((panel: any, idx: number) => {
        const info = panel.panelInfo || {};
        const planPanelId = info.panel_id || info.planPanelId || '';
        const shots = info.shots || [];
        const isGroupedPanel = shots.length > 1;

        // 新流程：从 shots 中提取角色
        const allCharacters = shots.flatMap((s: any) => s.characters || []);
        const uniqueCharNames = [...new Set(allCharacters)] as string[];

        // 新流程：分组显示 "N 个分镜 · Xs"，旧流程用原始字段
        const synopsis = isGroupedPanel
          ? `${shots.length} 个分镜 · ${info.totalDuration || shots.reduce((s: number, sh: any) => s + (sh.duration || 0), 0)}s`
          : (info.scene_summary || (planPanelId ? sceneSummaryMap[planPanelId] : '') || info.composition || shots[0]?.visualDescription || '');

        // 新流程缩略图：融合图 > splitImageUrl > gridImages
        const thumbnail = info.fusionImageUrl
          || shots[0]?.splitImageUrl
          || (info.gridImages?.length > 0 ? info.gridImages[0] : null);

        const videoUrl = info.videoUrl || null;
        const videoStatus = info.videoStatus || null;
        const gridImages = info.gridImages || [];
        const gridStatus = info.gridStatus || 'pending';
        const fusionImageUrl = info.fusionImageUrl || null;

        return {
          segmentIndex: idx,
          title: isGroupedPanel ? `分组 ${idx + 1}` : `分镜 ${idx + 1}`,
          synopsis,
          sceneThumbnail: thumbnail,
          characterAvatars: uniqueCharNames.map((name: string) => ({
            charId: charNameToIdMap[name] || '',
            name,
            avatarUrl: (charNameToIdMap[name] && charAvatarMap[charNameToIdMap[name]]) || '',
          })),
          pipelineStep: mapGridToPipelineStep({
            gridStatus: gridStatus,
            videoStatus: videoStatus || 'pending',
          }),
          gridImages,
          gridStatus,
          fusionImageUrl,
          shots,
          videoUrl,
          feedback: info.revisionFeedback || '',
          panelData: {
            panelId: String(panel.id),
            planPanelId,
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

      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep =>
            ep.episodeId === episodeId ? { ...ep, segments } : ep
          ),
        }))
      );
    } catch (err) {
      console.error(`加载集 ${episodeId} 分镜失败:`, err);
    }

    // 加载涉及的角色头像
    if (charIds.length > 0) {
      loadCharacterAvatars(charIds);
    }
  }, [projectId, charAvatarMap, charNameToIdMap]);

  /**
   * 手动刷新分镜（清除缓存重新加载）
   */
  const handleRefreshPanels = useCallback(async (episodeId: number) => {
    panelsLoadedRef.current.delete(episodeId);
    await loadPanelsForEpisode(episodeId);
  }, [loadPanelsForEpisode]);

  /**
   * 切换集数展开/收起（手风琴模式）
   */
  const toggleEpisode = useCallback((episodeId: number) => {
    setExpansion(prev => {
      const willExpand = prev.expandedEpisodeId !== episodeId;
      return {
        expandedEpisodeId: willExpand ? episodeId : null,
        expandedSegmentKey: null,
      };
    });
  }, []);

  // charAvatarMap 更新后，刷新已有 segments 的角色头像
  useEffect(() => {
    if (Object.keys(charAvatarMap).length === 0) return;
    setChapters(prev =>
      prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep => ({
          ...ep,
          segments: ep.segments.map(seg => ({
            ...seg,
            characterAvatars: seg.characterAvatars.map(a => ({
              ...a,
              avatarUrl: charAvatarMap[a.charId] || a.avatarUrl,
            })),
          })),
        })),
      }))
    );
  }, [charAvatarMap]);

  /**
   * 刷新某集所有 Panel 的生产状态
   */
  const refreshProductionStatuses = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    try {
      const res = await getBatchProductionStatuses(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;

      const statusMap = new Map<number, any>();
      res.data.forEach((s: any) => statusMap.set(s.panelId, s));

      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep =>
            ep.episodeId === episodeId
              ? {
                  ...ep,
                  segments: ep.segments.map(seg => {
                    const panelId = Number(seg.panelData?.panelId);
                    const status = statusMap.get(panelId);
                    if (!status) return seg;

                    // Use gridStatus/videoStatus from the response
                    const gridStatus = status.gridStatus || seg.gridStatus;
                    const videoStatus = status.videoStatus || 'pending';

                    return {
                      ...seg,
                      pipelineStep: mapGridToPipelineStep({ gridStatus, videoStatus }),
                      gridImages: status.gridImages?.length ? status.gridImages : seg.gridImages,
                      gridStatus,
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
        }))
      );
    } catch (err) {
      console.error('刷新生产状态失败:', err);
    }
  }, [projectId]);

  // 展开集数时自动加载分镜
  useEffect(() => {
    if (expansion.expandedEpisodeId !== null) {
      loadPanelsForEpisode(expansion.expandedEpisodeId);
      refreshProductionStatuses(expansion.expandedEpisodeId);
    }
  }, [expansion.expandedEpisodeId, loadPanelsForEpisode, refreshProductionStatuses]);

  /**
   * 切换片段展开/收起
   */
  const toggleSegment = useCallback((segmentKey: string | null) => {
    setExpansion(prev => ({
      ...prev,
      expandedSegmentKey: segmentKey,
    }));
  }, []);

  /**
   * 审核通过九宫格
   */
  const handleApproveGrid = useCallback(async (episodeId: number, panelId: string) => {
    if (!projectId) return;
    try {
      await approveGrid(projectId, episodeId, Number(panelId));
      await refreshProductionStatuses(episodeId);
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '审核失败');
    }
  }, [projectId, refreshProductionStatuses]);

  /**
   * 退回九宫格
   */
  const handleRejectGrid = useCallback(async (episodeId: number, panelId: string, reason: string) => {
    if (!projectId) return;
    try {
      await rejectGrid(projectId, episodeId, Number(panelId), reason);
      await refreshProductionStatuses(episodeId);
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '退回失败');
    }
  }, [projectId, refreshProductionStatuses]);

  /**
   * 重新生成九宫格
   */
  const handleRegenerateGrid = useCallback(async (episodeId: number, panelId: string) => {
    if (!projectId) return;
    setGeneratingGridPanelId(panelId);
    try {
      await regenerateGrid(projectId, episodeId, Number(panelId));
      // 基于状态的轮询，检查是否生成完成或失败
      const abort = new AbortController();
      let retries = 0;
      const poll = async () => {
        while (retries < GRID_POLL_MAX_RETRIES && !abort.signal.aborted) {
          await new Promise(r => setTimeout(r, VIDEO_POLL_INTERVAL));
          if (abort.signal.aborted) return;
          retries++;
          try {
            const res = await getBatchProductionStatuses(projectId, episodeId);
            if ((res.code !== 0 && res.code !== 200) || !res.data) continue;

            const panelStatus = res.data.find((s: any) => s.panelId === Number(panelId));
            if (panelStatus) {
              const gs = panelStatus.gridStatus;
              if (gs === 'generated' || gs === 'approved') {
                await refreshProductionStatuses(episodeId);
                setGeneratingGridPanelId(null);
                return;
              }
              if (gs === 'failed') {
                alert('九宫格重新生成失败');
                setGeneratingGridPanelId(null);
                return;
              }
            }
          } catch {
            // 继续轮询
          }
        }
        if (retries >= GRID_POLL_MAX_RETRIES) {
          console.warn('九宫格重新生成轮询超时: panelId=', panelId);
          setGeneratingGridPanelId(null);
        }
      };
      poll();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '重新生成失败');
      setGeneratingGridPanelId(null);
    }
  }, [projectId, refreshProductionStatuses]);

  /**
   * 审核通过整集九宫格
   */
  const handleApproveEpisodeGrid = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    try {
      await approveEpisodeGrid(projectId, episodeId);
      panelsLoadedRef.current.delete(episodeId);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '审核失败');
    }
  }, [projectId, loadEpisodes]);

  /**
   * 拒绝整集九宫格（新流程）
   */
  const handleRejectEpisodeGrid = useCallback(async (episodeId: number, reason: string) => {
    if (!projectId) return;
    try {
      await rejectEpisodeGrid(projectId, episodeId, reason);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '退回失败');
    }
  }, [projectId, loadEpisodes]);

  /**
   * 重新生成整集九宫格（新流程）
   */
  const handleRegenerateEpisodeGrid = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    try {
      await regenerateEpisodeGrid(projectId, episodeId);
      const abort = new AbortController();
      let retries = 0;
      const poll = async () => {
        while (retries < GRID_POLL_MAX_RETRIES && !abort.signal.aborted) {
          await new Promise(r => setTimeout(r, GRID_POLL_INTERVAL));
          if (abort.signal.aborted) return;
          retries++;
          try {
            const res = await getEpisodeGridStatus(projectId, episodeId);
            const gridStatus = res.data?.gridStatus;
            if (gridStatus === 'generated' || gridStatus === 'approved') {
              await loadEpisodes();
              return;
            }
            if (gridStatus === 'failed') {
              alert('九宫格重新生成失败');
              await loadEpisodes();
              return;
            }
          } catch {
            // 继续轮询
          }
        }
        if (retries >= GRID_POLL_MAX_RETRIES) {
          console.warn('整集九宫格重新生成轮询超时: episodeId=', episodeId);
          await loadEpisodes();
        }
      };
      poll();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '重新生成失败');
    }
  }, [projectId, loadEpisodes]);

  /**
   * 刷新整集九宫格状态（轮询用）
   */
  const refreshEpisodeGridStatus = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    try {
      const res = await getEpisodeGridStatus(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const data = res.data;
      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep =>
            ep.episodeId === episodeId
              ? {
                  ...ep,
                  gridStatus: data.gridStatus,
                  gridImages: data.gridImages || [],
                  splitShots: data.splitShots || [],
                  gridRejectionFeedback: data.gridRejectionFeedback,
                }
              : ep
          ),
        }))
      );
    } catch {
      // 静默失败
    }
  }, [projectId]);

  /**
   * 生成视频
   */
  const handleGenerateVideo = useCallback(async (episodeId: number, panelId: string, customPrompt?: string) => {
    if (!projectId) return;
    setGeneratingVideoPanelId(panelId);
    try {
      await generateVideo(projectId, episodeId, Number(panelId), offPeak, customPrompt);
      // 基于状态的轮询，检查是否生成完成或失败
      const abort = new AbortController();
      let retries = 0;
      const poll = async () => {
        while (retries < VIDEO_POLL_MAX_RETRIES && !abort.signal.aborted) {
          await new Promise(r => setTimeout(r, VIDEO_POLL_INTERVAL));
          if (abort.signal.aborted) return;
          retries++;
          try {
            // 获取该panel的生产状态
            const res = await getBatchProductionStatuses(projectId, episodeId);
            if ((res.code !== 0 && res.code !== 200) || !res.data) continue;

            const panelStatus = res.data.find((s: any) => s.panelId === Number(panelId));
            if (panelStatus) {
              const videoStatus = panelStatus.videoStatus;
              // 检查状态
              if (videoStatus === 'completed') {
                // 生成成功
                await refreshProductionStatuses(episodeId);
                setGeneratingVideoPanelId(null);
                return;
              }
              if (videoStatus === 'failed') {
                // 生成失败
                alert('视频生成失败');
                setGeneratingVideoPanelId(null);
                return;
              }
            }
          } catch {
            // 继续轮询
          }
        }
        if (retries >= VIDEO_POLL_MAX_RETRIES) {
          console.warn('视频生成轮询超时: panelId=', panelId);
          setGeneratingVideoPanelId(null);
        }
      };
      poll();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '生成视频失败');
      setGeneratingVideoPanelId(null);
    }
  }, [projectId, refreshProductionStatuses, offPeak]);

  /**
   * 加载视频提示词
   */
  const handleLoadVideoPrompt = useCallback(async (episodeId: number, segmentIndex: number): Promise<string> => {
    if (!projectId) return '';
    try {
      // 查找对应的 segment 获取 panelId
      const episode = chapters.flatMap(ch => ch.episodes).find(ep => ep.episodeId === episodeId);
      const panelId = episode?.segments[segmentIndex]?.panelData?.panelId;
      if (!panelId) return '(无法找到分镜)';

      const res = await getVideoPrompt(projectId, episodeId, Number(panelId));
      if ((res.code !== 0 && res.code !== 200) || !res.data?.prompt) {
        return '(无法加载提示词)';
      }
      return res.data.prompt;
    } catch (err: any) {
      console.error('加载提示词失败:', err);
      return '(加载失败)';
    }
  }, [projectId, chapters]);

  /**
   * 渲染集数卡片
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
        onSegmentApproveGrid={(epId, segIdx) => {
          const panelId = episode.segments[segIdx]?.panelData?.panelId;
          if (panelId) handleApproveGrid(epId, panelId);
        }}
        onSegmentRejectGrid={(epId, segIdx, reason) => {
          const panelId = episode.segments[segIdx]?.panelData?.panelId;
          if (panelId) handleRejectGrid(epId, panelId, reason);
        }}
        onSegmentRegenerateGrid={(epId, segIdx) => {
          const panelId = episode.segments[segIdx]?.panelData?.panelId;
          if (panelId) handleRegenerateGrid(epId, panelId);
        }}
        onSegmentGenerateVideo={(epId, segIdx, customPrompt) => {
          const panelId = episode.segments[segIdx]?.panelData?.panelId;
          if (panelId) handleGenerateVideo(epId, panelId, customPrompt);
        }}
        onSegmentLoadVideoPrompt={handleLoadVideoPrompt}
        onRefreshPanels={handleRefreshPanels}
        generatingGridPanelId={generatingGridPanelId}
        generatingVideoPanelId={generatingVideoPanelId}
        onApproveEpisodeGrid={() => handleApproveEpisodeGrid(episode.episodeId)}
        onRejectEpisodeGrid={(reason: string) => handleRejectEpisodeGrid(episode.episodeId, reason)}
        onRegenerateEpisodeGrid={() => handleRegenerateEpisodeGrid(episode.episodeId)}
        onRefreshEpisodeGrid={() => refreshEpisodeGridStatus(episode.episodeId)}
      />
    );
  };

  /**
   * 渲染片段完成状态圆点
   */
  const renderCompletionDot = (step: SegmentState['pipelineStep']) => {
    if (step === 'video_completed') {
      return <span className={styles.dotCompleted} />;
    }
    if (step === 'video_generating' || step === 'grid_review' || step === 'grid_approved' || step === 'grid_generating') {
      return <span className={styles.dotInProgress} />;
    }
    return <span className={styles.dotPending} />;
  };

  // 流水线生成中状态（分集剧本/分镜正在生成）
  const isGeneratingScript = statusInfo?.statusCode === 'EPISODE_SCRIPT_GENERATING'
    || statusInfo?.statusCode === 'STORYBOARD_GENERATING';
  if (isGeneratingScript && !isPipelineFailed) {
    const label = statusInfo?.statusCode === 'EPISODE_SCRIPT_GENERATING'
      ? '正在生成分集剧本...' : '正在生成分镜脚本...';
    return (
      <div className={styles.pageContainer}>
        <div className={styles.loadingState}>
          <div className={styles.spinner} />
          <p>{label}</p>
          <p style={{ color: '#888', fontSize: 13, marginTop: 4 }}>AI 正在创作中，通常需要 1-3 分钟</p>
        </div>
      </div>
    );
  }

  // 流水线失败状态（分集剧本/分镜生成失败）
  if (isPipelineFailed && !loading) {
    const failedLabel = failedStatusCode === 'EPISODE_SCRIPT_GENERATING_FAILED'
      ? '分集剧本生成失败' : '分镜生成失败';
    return (
      <div className={styles.pageContainer}>
        <div className={styles.errorState}>
          <h3 style={{ color: '#ef4444', marginBottom: 8 }}>{failedLabel}</h3>
          <p>{statusInfo?.statusDescription || 'AI 生成过程中出现错误，请重试。'}</p>
          <button
            onClick={handleRetryPipeline}
            className={styles.retryButton}
            disabled={retrying}
          >
            {retrying ? '重试中...' : '重试生成'}
          </button>
        </div>
      </div>
    );
  }

  // 加载中状态
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

  // 错误状态
  if (error) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.errorState}>
          <p>{error}</p>
          <button onClick={loadEpisodes} className={styles.retryButton}>
            重试
          </button>
        </div>
      </div>
    );
  }

  // 空状态
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
      {/* 顶部标题和统计栏 */}
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
          <button
            className={`${styles.offPeakToggle} ${offPeak ? styles.offPeakActive : ''}`}
            onClick={toggleOffPeak}
            title={offPeak ? '错峰模式已开启：积分更低，48小时内生成' : '错峰模式已关闭：即时生成'}
          >
            <span className={styles.toggleTrack}>
              <span className={styles.toggleThumb} />
            </span>
            <span className={styles.toggleLabel}>错峰</span>
          </button>
        </div>
      </div>

      {/* 批量审核栏 */}
      {newFlowEpisodes.length > 0 && (
        <BatchReviewBar
          totalPanels={newFlowEpisodes.length}
          approvedCount={approvedGridEpisodes}
          pendingReviewCount={pendingGridEpisodes}
          onApproveAll={() => {
            const pending = newFlowEpisodes.filter(
              ep => ep.gridStatus === 'generated' || ep.gridStatus === 'rejected'
            );
            pending.forEach(ep => handleApproveEpisodeGrid(ep.episodeId));
          }}
        />
      )}

      {/* 章节列表 */}
      <div className={styles.chapterList}>
        {chapters.map(chapter => {
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

              <div className={styles.episodeList}>
                {chapter.episodes.map(ep => renderEpisodeCard(chapter.chapterIndex, ep))}
              </div>
            </div>
          );
        })}
      </div>

      {/* 底部操作栏：所有视频完成时显示下一步按钮 */}
      {totalSegments > 0 && completedSegments === totalSegments && onNextStep && (
        <div className={styles.footerActions}>
          <button className={styles.nextStepButton} onClick={handleNextStep} disabled={advancing}>
            {advancing ? '正在跳转...' : '下一步：视频拼接 →'}
          </button>
        </div>
      )}
    </div>
  );
};

export default Step5page;
