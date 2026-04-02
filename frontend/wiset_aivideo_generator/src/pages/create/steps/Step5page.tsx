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
  enhanceVideoPrompt,
} from '../../../services/episodeService';
import { getCharacterStatus, getCharacters } from '../../../services/characterService';
import { advanceStatus } from '../../../services/projectService';
import { useCreateStore } from '../../../stores/createStore';
import EpisodeCard from './components/EpisodeCard';
import { BatchReviewBar } from './components/BatchReviewBar';
import { useSseProgress } from './hooks/useSseProgress';

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
  const isPipelineFailed = statusInfo?.isFailed ?? false;
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
  const [approvingEpisodeId, setApprovingEpisodeId] = useState<number | null>(null);

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

  // 生成中状态保护：避免 loadEpisodes 用空数据覆盖 SSE 占位
  const isGeneratingRef = useRef(false);
  isGeneratingRef.current = statusInfo?.isGenerating ?? false;

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

      // 生成中后端事务未提交，getEpisodes 返回空 → 保留 SSE 创建的占位数据
      if (items.length === 0 && isGeneratingRef.current) {
        // 不覆盖已有数据（SSE 创建的占位 chapters）
        setChapters(prev => prev.length > 0 ? prev : prev);
        setLoading(false);
        return;
      }

      // 按章节分组
      const chapterMap = new Map<string, any[]>();
      items.forEach(ep => {
        const chapterTitle = ep.episodeInfo?.chapterTitle?.trim() || '未分章';
        if (!chapterMap.has(chapterTitle)) {
          chapterMap.set(chapterTitle, []);
        }
        chapterMap.get(chapterTitle)!.push(ep);
      });

      // 去重：同一章节内 episodeNum 相同的 episode 合并（后端可能因 title 不匹配而创建新记录）
      for (const [_, episodeList] of chapterMap.entries()) {
        if (episodeList.length <= 1) continue;
        const seen = new Map<number, number>(); // episodeNum → index
        const deduped: any[] = [];
        for (const ep of episodeList) {
          const num = ep.episodeInfo?.episodeNum;
          if (num == null) {
            deduped.push(ep);
            continue;
          }
          const prevIdx = seen.get(num);
          if (prevIdx == null) {
            seen.set(num, deduped.length);
            deduped.push(ep);
          } else {
            // 合并：优先保留有标题的 episodeInfo，补充缺失字段
            const prev = deduped[prevIdx];
            const prevInfo = prev.episodeInfo || {};
            const curInfo = ep.episodeInfo || {};
            // 标题取非空的那边
            if (!prevInfo.title && curInfo.title) prevInfo.title = curInfo.title;
            // grid 数据取有内容的那边
            if (!prevInfo.gridStatus && curInfo.gridStatus) prevInfo.gridStatus = curInfo.gridStatus;
            if ((!prevInfo.gridImages || prevInfo.gridImages.length === 0) && curInfo.gridImages?.length) {
              prevInfo.gridImages = curInfo.gridImages;
            }
            if ((!prevInfo.splitShots || prevInfo.splitShots.length === 0) && curInfo.splitShots?.length) {
              prevInfo.splitShots = curInfo.splitShots;
            }
            if (!prevInfo.panelPlan && curInfo.panelPlan) prevInfo.panelPlan = curInfo.panelPlan;
            // 保留较大的 id（通常新创建的 id 更大）
            if (ep.id > prev.id) prev.id = ep.id;
            prev.episodeInfo = prevInfo;
          }
        }
        episodeList.length = 0;
        episodeList.push(...deduped);
      }

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
      // 清除缓存，确保事务提交后的新数据能被重新加载
      panelsLoadedRef.current.clear();
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
      ep.gridStatus === 'generating' && ep.episodeId > 0
    );
    if (!generatingGridEp) return;

    const gridEpId = generatingGridEp.episodeId;
    console.info('检测到正在生成中的 episode 九宫格, 恢复轮询: epId=', gridEpId);
    const abort = new AbortController();
    let retries = 0;
    const gridPoll = async () => {
      while (retries < GRID_POLL_MAX_RETRIES && !abort.signal.aborted) {
        await new Promise(r => setTimeout(r, GRID_POLL_INTERVAL));
        if (abort.signal.aborted) return;
        retries++;
        try {
          const status = await refreshEpisodeGridStatus(gridEpId);
          if (status === 'generated' || status === 'approved' || status === 'failed') {
            // 生成完成后重新加载分镜数据
            panelsLoadedRef.current.delete(gridEpId);
            loadPanelsForEpisode(gridEpId);
            refreshProductionStatuses(gridEpId);
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    chapters.length,
    // 包含所有正在 generating 的 episodeId，确保 grid 状态变化时重新触发
    ...chapters.flatMap(ch => ch.episodes.filter(ep => ep.gridStatus === 'generating').map(ep => ep.episodeId)),
  ]);

  /**
   * 加载项目角色列表，同时构建 name→ID 映射和头像 URL 映射
   * 提前加载头像 URL，避免面板加载时的竞态条件
   */
  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await getCharacters(projectId, { page: 1, size: 100 });
        if (cancelled) return;
        const items = res.data?.items || [];

        // 1. 构建 name→ID 映射（精确 + 模糊：去除括号后缀）
        const nameMap: Record<string, string> = {};
        const allCharIds: string[] = [];
        items.forEach((c: any) => {
          if (c.charId && c.name) {
            const name = c.name.trim();
            nameMap[name] = c.charId;
            // 模糊匹配：如 "墨尘（幻影）" → 也映射 "墨尘"
            const stripped = name.replace(/[（(][^）)]*[）)]$/, '').trim();
            if (stripped && stripped !== name) {
              if (!nameMap[stripped]) nameMap[stripped] = c.charId;
            }
            allCharIds.push(c.charId);
          }
        });
        if (Object.keys(nameMap).length > 0) {
          setCharNameToIdMap(nameMap);
        }

        // 2. 并行加载所有角色头像 URL（提前加载，避免面板加载时的竞态）
        if (allCharIds.length > 0) {
          const results = await Promise.allSettled(
            allCharIds.map(charId => getCharacterStatus(projectId, charId))
          );
          if (cancelled) return;
          const avatarMap: Record<string, string> = {};
          results.forEach((r, i) => {
            if (r.status === 'fulfilled' && r.value?.data) {
              const d = r.value.data;
              const url = d.threeViewGridUrl || d.expressionGridUrl || '';
              if (url) {
                avatarMap[allCharIds[i]] = url;
              }
            }
          });
          if (Object.keys(avatarMap).length > 0) {
            setCharAvatarMap(prev => ({ ...prev, ...avatarMap }));
          }
        }
      } catch {
        // 静默失败
      }
    })();
    return () => { cancelled = true; };
  }, [projectId]);

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

  // 轮询 AbortController 引用，组件卸载时清理
  const regenerateGridAbortRef = useRef<AbortController | null>(null);
  const regenerateEpisodeGridAbortRef = useRef<AbortController | null>(null);
  const generateVideoAbortRef = useRef<AbortController | null>(null);
  useEffect(() => {
    return () => {
      regenerateGridAbortRef.current?.abort();
      regenerateEpisodeGridAbortRef.current?.abort();
      generateVideoAbortRef.current?.abort();
    };
  }, []);

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

  // charNameToIdMap 或 charAvatarMap 更新后，统一修正已有 segments 的角色 charId 和头像 URL
  // 无论面板加载时这两个 map 是否就绪，此 effect 都能正确回填
  useEffect(() => {
    if (Object.keys(charNameToIdMap).length === 0 && Object.keys(charAvatarMap).length === 0) return;
    setChapters(prev =>
      prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep => ({
          ...ep,
          segments: ep.segments.map(seg => ({
            ...seg,
            characterAvatars: seg.characterAvatars.map(a => {
              const charId = a.charId || charNameToIdMap[a.name] || '';
              const avatarUrl = charAvatarMap[charId] || a.avatarUrl;
              if (charId === a.charId && avatarUrl === a.avatarUrl) return a;
              return { ...a, charId, avatarUrl };
            }),
          })),
        })),
      }))
    );
  }, [charAvatarMap, charNameToIdMap]);

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
  const handleRegenerateGrid = useCallback(async (episodeId: number, panelId: string, customHint?: string) => {
    if (!projectId) return;
    setGeneratingGridPanelId(panelId);
    try {
      await regenerateGrid(projectId, episodeId, Number(panelId), customHint);
      // 基于状态的轮询，检查是否生成完成或失败
      const abort = new AbortController();
      regenerateGridAbortRef.current = abort;
      let retries = 0;
      const poll = async () => {
        while (retries < GRID_POLL_MAX_RETRIES && !abort.signal.aborted) {
          await new Promise(r => setTimeout(r, GRID_POLL_INTERVAL));
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
    setApprovingEpisodeId(episodeId);
    try {
      await approveEpisodeGrid(projectId, episodeId);
      panelsLoadedRef.current.delete(episodeId);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '审核失败');
    } finally {
      setApprovingEpisodeId(null);
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
      regenerateEpisodeGridAbortRef.current = abort;
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
   * @returns 最新的 gridStatus，失败返回 null
   */
  const refreshEpisodeGridStatus = useCallback(async (episodeId: number): Promise<string | null> => {
    if (!projectId) return null;
    try {
      const res = await getEpisodeGridStatus(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return null;
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
                  isNewFlow: true,
                }
              : ep
          ),
        }))
      );
      return data.gridStatus;
    } catch {
      // 静默失败
      return null;
    }
  }, [projectId]);

  // SSE 实时进度订阅（放在 loadPanelsForEpisode 定义之后，确保回调可引用）
  useSseProgress(projectId, {
    onEpisodeScriptDone: (data) => {
      setChapters(prev => {
        const epIndex = data.episodeNum;
        const epTitle = data.title || `第${epIndex}集`;
        // 确保 chapters 不为空
        if (prev.length === 0) {
          return [{
            chapterIndex: 1,
            title: '生成中',
            episodes: [{
              episodeId: 0,
              episodeIndex: epIndex,
              title: epTitle,
              sceneSummaryMap: {},
              segments: [],
              scriptStatus: 'done' as const,
              storyboardStatus: 'pending' as const,
            }],
          }];
        }
        // 查找是否已有该 episodeIndex 的 episode
        let found = false;
        const updated = prev.map(ch => {
          const hasEp = ch.episodes.some(ep => ep.episodeIndex === epIndex);
          if (hasEp) {
            found = true;
            return {
              ...ch,
              episodes: ch.episodes.map(ep =>
                ep.episodeIndex === epIndex
                  ? { ...ep, scriptStatus: 'done' as const, title: epTitle }
                  : ep
              ),
            };
          }
          return ch;
        });
        if (found) return updated;
        // 没有 → 在第一个 chapter 中添加占位 episode
        updated[0] = {
          ...updated[0],
          episodes: [...updated[0].episodes, {
            episodeId: 0,
            episodeIndex: epIndex,
            title: epTitle,
            sceneSummaryMap: {},
            segments: [],
            scriptStatus: 'done' as const,
            storyboardStatus: 'pending' as const,
          }],
        };
        return updated;
      });
    },
    onEpisodePanelDone: (data) => {
      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep => {
            // 先按 episodeId 匹配，再按 episodeNum 匹配
            if (ep.episodeId === data.episodeId || ep.episodeIndex === data.episodeNum) {
              return {
                ...ep,
                episodeId: data.episodeId || ep.episodeId,
                storyboardStatus: 'done' as const,
              };
            }
            return ep;
          }),
        }))
      );
      // 加载该集的分镜（后端已提交事务，episode 可查询）
      if (data.episodeId) {
        const tryLoad = async (retries = 0) => {
          panelsLoadedRef.current.delete(data.episodeId);
          try {
            await loadPanelsForEpisode(data.episodeId);
          } catch {
            if (retries < 2) {
              await new Promise(r => setTimeout(r, 1500));
              tryLoad(retries + 1);
            }
          }
        };
        tryLoad();
      }
    },
    onEpisodeGridStatus: (data) => {
      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep =>
            // 按 episodeId 匹配，再用 episodeNum 兜底
            (ep.episodeId === data.episodeId || (data.episodeNum && ep.episodeIndex === data.episodeNum))
              ? {
                  ...ep,
                  episodeId: data.episodeId || ep.episodeId,
                  gridStatus: data.gridStatus as any,
                  isNewFlow: true,
                }
              : ep
          ),
        }))
      );
      // generating/generated/failed 都需要刷新完整 grid 数据
      if (data.episodeId) {
        refreshEpisodeGridStatus(data.episodeId);
      }
      // generated 或 failed 时还需要重新加载分镜数据
      if ((data.gridStatus === 'generated' || data.gridStatus === 'failed' || data.gridStatus === 'approved') && data.episodeId) {
        panelsLoadedRef.current.delete(data.episodeId);
        loadPanelsForEpisode(data.episodeId);
        refreshProductionStatuses(data.episodeId);
      }
    },
    onStatusChange: (data) => {
      if (projectId && data.to) {
        syncStatus(projectId);
        loadEpisodes();
      }
    },
    onReconnect: () => {
      // SSE 重连后全量刷新
      if (projectId) {
        syncStatus(projectId);
        loadEpisodes();
      }
    },
  });

  /**
   * 生成视频
   */
  const handleGenerateVideo = useCallback(async (episodeId: number, panelId: string, customPrompt?: string) => {
    if (!projectId) return;
    setGeneratingVideoPanelId(panelId);

    // 先启动轮询，再发 API 请求 —— 确保即使 API 延迟也不会阻塞进度更新
    const abort = new AbortController();
    generateVideoAbortRef.current = abort;
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
            // 每次轮询都刷新 UI，确保 videoProgress 等中间状态实时显示
            await refreshProductionStatuses(episodeId);
            // 检查终态
            if (videoStatus === 'completed') {
              setGeneratingVideoPanelId(null);
              return;
            }
            if (videoStatus === 'failed') {
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

    // 发起视频生成请求（fire-and-forget，不阻塞轮询）
    generateVideo(projectId, episodeId, Number(panelId), offPeak, customPrompt)
      .catch((err: any) => {
        abort.abort();
        alert(err?.response?.data?.message || err?.message || '生成视频失败');
        setGeneratingVideoPanelId(null);
      });
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
   * AI 优化视频提示词（消耗1积分）
   */
  const handleEnhanceVideoPrompt = useCallback(async (episodeId: number, segmentIndex: number): Promise<string> => {
    if (!projectId) return '';
    try {
      const episode = chapters.flatMap(ch => ch.episodes).find(ep => ep.episodeId === episodeId);
      const panelId = episode?.segments[segmentIndex]?.panelData?.panelId;
      if (!panelId) throw new Error('无法找到分镜');

      const res = await enhanceVideoPrompt(projectId, episodeId, Number(panelId));
      if ((res.code !== 0 && res.code !== 200) || !res.data?.prompt) {
        throw new Error('增强失败');
      }
      return res.data.prompt;
    } catch (err: any) {
      console.error('提示词增强失败:', err);
      throw err;
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
        onSegmentRegenerateGrid={(epId, segIdx, customHint) => {
          const panelId = episode.segments[segIdx]?.panelData?.panelId;
          if (panelId) handleRegenerateGrid(epId, panelId, customHint);
        }}
        onSegmentGenerateVideo={(epId, segIdx, customPrompt) => {
          const panelId = episode.segments[segIdx]?.panelData?.panelId;
          if (panelId) handleGenerateVideo(epId, panelId, customPrompt);
        }}
        onSegmentLoadVideoPrompt={handleLoadVideoPrompt}
        onSegmentEnhanceVideoPrompt={handleEnhanceVideoPrompt}
        onRefreshPanels={handleRefreshPanels}
        generatingGridPanelId={generatingGridPanelId}
        generatingVideoPanelId={generatingVideoPanelId}
        isApprovingGrid={approvingEpisodeId === episode.episodeId}
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

  // 流水线失败状态（分集剧本/分镜生成失败）
  if (isPipelineFailed && !loading) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.errorState}>
          <h3 style={{ color: '#ef4444', marginBottom: 8 }}>生成失败</h3>
          <p>{statusInfo?.errorMessage || statusInfo?.statusDescription || 'AI 生成过程中出现错误，请重试。'}</p>
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

  // 空状态（生成中不显示"暂无数据"，让进度条可见）
  const isGenerating = statusInfo?.isGenerating ?? false;
  if (chapters.length === 0 && !isGenerating) {
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

      {/* 生成进度提示条（替代原有的阻塞性 spinner） */}
      {statusInfo?.isGenerating && (
        <div className={styles.generationProgress}>
          <span className={styles.progressSpinner} />
          <span>
            {statusInfo?.statusDescription || '正在生成中...'}
          </span>
          <span className={styles.generationCount}>
            {chapters.reduce((sum, ch) => sum + ch.episodes.filter(ep =>
              ep.storyboardStatus === 'done' || ep.gridStatus
            ).length, 0)}
            {' / '}
            {chapters.reduce((sum, ch) => sum + ch.episodes.length, 0)} 集已完成
          </span>
        </div>
      )}

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
