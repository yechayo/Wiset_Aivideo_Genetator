/**
 * Step4Production - 分镜生产 Tab 子阶段
 * Tab 4a: 脚本生成/审核
 * Tab 4b: 九宫格图片生成/审核
 * Tab 4c: 视频生成/确认
 */
import React, { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import styles from './Step4Production.module.less';
import type { ChapterState, EpisodeState, PipelineStage, SegmentState } from './types';
import { getPipelineStage } from './types';
import {
  getEpisodes,
  getPanels,
  getBatchProductionStatuses,
  approvePanel,
  approveEpisodeGrid,
  rejectEpisodeGrid,
  regenerateEpisodeGrid,
  regenerateEpisodeGridPage,
  generateVideo,
  generateVideoRef,
  generateEpisodeScripts,
  getVideoPrompt,
  enhanceVideoPrompt as enhanceVideoPromptApi,
  generatePanelTts,
  batchGenerateTts,
  mergePanelAudio,
  batchMergeAudio,
  rejectToScript,
} from '../../../services/episodeService';
import { advanceStatus, updateProject } from '../../../services/projectService';
import { useCreateStore } from '../../../stores/createStore';
import { useSseProgress } from './hooks/useSseProgress';
import ScriptEpisodeCard from './components/ScriptEpisodeCard';
import GridEpisodeCard from './components/GridEpisodeCard';
import VideoSegmentRow from './components/VideoSegmentRow';

type SubPhase = 'script' | 'grid' | 'video';

const VIDEO_POLL_INTERVAL = 3000;
const VIDEO_POLL_MAX_RETRIES = 720;

interface Step4ProductionProps {
  project: any;
  onNextStep?: () => void;
}

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

const SpinIcon = () => <span className={styles.btnSpinner} />;

const STEPS = [
  { key: 'script' as SubPhase, number: 'a', label: '脚本生成' },
  { key: 'grid' as SubPhase, number: 'b', label: '宫格图片' },
  { key: 'video' as SubPhase, number: 'c', label: '视频生成' },
];

// ==================== Prompt 构建器（与后端 PanelPromptBuilder 保持一致）====================

const STYLE_PREFIX_MAP: Record<string, string> = {
  REAL: '写实风格，电影级摄影质感，8K超高清分辨率，专业摄影级别，自然光效，体积光，柔和阴影，景深效果，色彩真实。',
  '3D': '3D渲染风格，Octane渲染，光线追踪，全局光照，8K超高清分辨率，影棚灯光，HDRI环境光，环境光遮蔽，PBR材质质感。',
  ANIME: '日系动漫风格，动漫背景艺术，高质量，杰作级别，精细插画，柔光效果，轮廓光，色彩鲜艳丰富，干净线条，清晰轮廓。',
  MANGA: '日系动漫风格，动漫背景艺术，高质量，杰作级别，精细插画，柔光效果，轮廓光，色彩鲜艳丰富，干净线条，清晰轮廓。',
  INK: '中国水墨画风格，水墨写意，高质量，杰作级别，精细插画，柔光效果，意境深远，墨色浓淡有致。',
  CYBERPUNK: '赛博朋克动漫风格，霓虹灯光，未来感，高质量，杰作级别，精细插画，柔光效果，轮廓光，色彩鲜艳丰富，暗色调对比。',
};

const toCircledNumber = (n: number): string => {
  const circled = ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨'];
  return n >= 1 && n <= 9 ? circled[n - 1] : String(n);
};

/** 构建九宫格图片生成提示词（与后端 PanelPromptBuilder / ComicCommentaryPanelPromptBuilder 一致） */
const buildGridPromptText = (visualStyle: string, shots: any[], isComicCommentary?: boolean, gridCols?: number, gridRows?: number): string => {
  const cols = gridCols ?? 3;
  const rows = gridRows ?? 3;
  const totalCells = cols * rows;
  const stylePrefix = STYLE_PREFIX_MAP[visualStyle] || '高质量，杰作级别，精细插画，柔光效果，色彩鲜艳。';
  const lines: string[] = [];

  lines.push(stylePrefix + '专业动画关键帧级别，电影级画面构图，精致光影与色彩。');
  lines.push('');

  lines.push('【布局要求 - 必须严格遵守】');
  lines.push(`输出一张严格 ${cols}×${rows} 宫格分镜图，图片必须为横屏宽高比 16:9（宽大于高），严禁竖屏或正方形输出。`);
  lines.push(`图片必须被 ${cols - 1} 条黑色竖线（约 4px 宽）和 ${rows - 1} 条黑色横线（约 4px 宽）均匀分割为 ${rows} 行 ${cols} 列，共 ${totalCells} 个等大的格子。`);
  lines.push('每个格子是一个完全独立的分镜画面，场景、人物、时间可以不同。');
  lines.push('绝对禁止：不要生成连续的、无分隔的大图。不要将多个场景混合在同一区域内。不要在格子之间绘制装饰性元素。');
  lines.push('图片中不包含任何文字、数字、标号或水印。');
  lines.push('');

  if (isComicCommentary) {
    lines.push('【景别约束】解说模式以中景、近景、特写为主；远景/大远景仅用于开场或转场，总数不超过 2 格。');
    lines.push('【字幕安全区】构图需留出上方约 1/4 区域，避免关键内容被花字遮挡。');
    lines.push('');
  }

  // 角色锚定（4a 阶段只有角色名，无外貌详情）
  const allChars = new Set<string>();
  shots.forEach(s => (s.characters || []).forEach((c: any) => {
    allChars.add(typeof c === 'string' ? c : c.name || '');
  }));
  if (allChars.size > 0) {
    lines.push('【角色设定 - 必须严格遵守】');
    lines.push('只允许绘制以下角色，绝对不要出现列表之外的角色、路人或背景人物。');
    lines.push('每个角色在不同格子中必须保持外貌、体型比例、服装、发型完全一致。');
    lines.push('');
    allChars.forEach(name => { if (name) lines.push(`- ${name}`); });
    lines.push('');
  }

  lines.push(`【分镜内容 - 按从左到右、从上到下填入${cols}×${rows}宫格，每个格子必须是精致的关键帧画面】`);
  lines.push('每个分镜必须包含：完整的场景环境细节（光影、色调、空间纵深）、角色的精确外貌与服装、细腻的面部表情和肢体语言、精心设计的构图与景深关系。画面要有电影级质感。');
  lines.push('');

  shots.forEach((shot, i) => {
    const row = Math.floor(i / cols) + 1;
    const col = i % cols + 1;
    let line = `第${row}行第${col}列: ${shot.sceneDescription || shot.visualDescription || ''}`;
    if (shot.shotSize) line += `，${shot.shotSize}`;
    if (shot.cameraAngle) line += `，${shot.cameraAngle}`;
    if (!shot.sceneDescription && shot.cameraMovement) line += `，${shot.cameraMovement}`;
    if (shot.scene) line += `，场景: ${shot.scene}`;
    lines.push(line);
    if (isComicCommentary) {
      const nar = shot.narration || (shot.speaker === '旁白' ? shot.dialogue : '');
      if (nar && nar !== '无') lines.push(`  解说旁白(口播): ${nar}`);
    }
  });

  const emptySlots = totalCells - shots.length;
  if (emptySlots > 0) {
    const FILLER_SCENES = [
      '远景 — 夕阳余晖洒在城市天际线上，暖色调，无角色',
      '中景 — 天空中云彩缓慢飘动，光线柔和，无角色',
      '特写 — 树叶在微风中轻轻摇曳，自然光影，无角色',
      '远景 — 宁静的湖面倒映着远山，柔和的色调，无角色',
      '中景 — 空旷的街道延伸到远方，傍晚的氛围，无角色',
      '特写 — 光线穿过窗帘的缝隙，尘埃在光束中漂浮，无角色',
      '远景 — 飞鸟划过天空的剪影，辽阔的视野，无角色',
      '中景 — 雨后地面倒映着霓虹灯光，柔和模糊，无角色',
    ];
    for (let i = 0; i < emptySlots; i++) {
      const r = Math.floor((shots.length + i) / cols) + 1;
      const c = (shots.length + i) % cols + 1;
      lines.push(`第${r}行第${c}列: ${FILLER_SCENES[i % FILLER_SCENES.length]}。`);
    }
    lines.push('');
  }

  lines.push('负面提示词：文字、水印、标签、签名、人体结构错误、肢体融合、多余手指、多余肢体、面部变形、眼睛异常、模糊、低质量、色块 artefact、粗糙线条、草稿感。');

  return lines.join('\n');
};

/** 构建多镜头视频生成提示词（与后端 PanelPromptBuilder / ComicCommentaryPanelPromptBuilder 一致） */
const buildMultiShotPromptText = (visualStyle: string, shots: any[], isComicCommentary?: boolean): string => {
  const stylePrefix = STYLE_PREFIX_MAP[visualStyle] || '高质量，杰作级别，精细插画，柔光效果，色彩鲜艳。';
  const lines: string[] = [];
  const n = shots.length;

  lines.push(isComicCommentary
    ? stylePrefix + ' 漫剧解说向连续视频：镜头以清晰叙事与情绪递进为主。'
    : stylePrefix + ' 专业电影级画面。');
  lines.push('');

  lines.push(`多镜头连续拍摄指令，以下 ${n} 个镜头必须在同一视频中连续呈现：`);
  lines.push('');

  shots.forEach((shot, i) => {
    lines.push(`【镜头${i + 1}】`);
    lines.push(`duration: ${shot.duration || 5}s`);
    if (shot.sceneDescription) {
      lines.push(`Scene: ${shot.sceneDescription}`);
    } else {
      lines.push(`Scene: ${shot.shotSize || ''}，${shot.cameraAngle || ''}，${shot.cameraMovement || ''}，${shot.visualDescription || ''}`);
    }

    const dialogue = typeof shot.dialogue === 'string' ? shot.dialogue : '';
    if (dialogue && dialogue !== '无') {
      const speaker = shot.speaker && shot.speaker !== '无' ? shot.speaker : '';
      const tone = shot.dialogueTone && shot.dialogueTone !== '无' ? shot.dialogueTone : '';
      let dLine = '对白';
      if (speaker) {
        dLine += `(${speaker}${tone ? `，${tone}` : ''})`;
      } else if (tone) {
        dLine += `(${tone})`;
      }
      dLine += `: ${dialogue}`;
      lines.push(dLine);
    }

    const audioEffects = shot.audioEffects;
    if (audioEffects && audioEffects !== '无') {
      lines.push(`音效: [${audioEffects}]`);
    }

    const transition = shot.transitionHint;
    if (transition && transition !== '无' && !transition.includes('最后一个镜头') && i < n - 1) {
      lines.push(`衔接: ${transition}`);
    }
    lines.push('');
  });

  lines.push('');

  lines.push('## 画面衔接');
  lines.push('多镜头间必须平滑过渡，严格遵循每个镜头的衔接提示。');
  lines.push('保持角色位置、动作、表情和情绪的连贯性。');

  let refLine = '参考图中编号';
  for (let i = 0; i < n; i++) refLine += toCircledNumber(i + 1);
  refLine += '分别对应';
  for (let i = 0; i < n; i++) {
    if (i > 0) refLine += '、';
    refLine += `【镜头${i + 1}】`;
  }
  refLine += '的画面内容。';
  lines.push(refLine);

  lines.push('');
  lines.push('## 负面提示词（严格遵守，违反任何一条即为失败）');
  lines.push('文字、水印、签名、logo、人体结构错误、肢体融合、多余手指、多余肢体、面部变形、眼睛异常、模糊、闪烁、低质量、色块 artefact。');
  if (isComicCommentary) {
    lines.push('禁止快速奔跑、剧烈运动、突然变向——运镜以缓慢推拉和微平移为主，用剪辑快切体现节奏。');
  } else {
    lines.push('禁止两人以上同框互动（拥抱、打斗、接触），多人互动必须拆分为单人反应镜头。');
    lines.push('禁止快速奔跑、剧烈运动、突然变向——镜头运动必须缓慢（缓慢推镜头、微平移、静止），用剪辑快切体现激烈而非画面快动。');
  }

  return lines.join('\n');
};

export default function Step4Production({ project, onNextStep }: Step4ProductionProps) {
  // Stable projectId: extract once, avoid prop object reference changes causing re-render loops
  const projectId = useMemo(() => project?.projectId, [project?.projectId]);
  const navigate = useNavigate();
  const isGenerating = useCreateStore(s => s.statusInfo?.isGenerating);
  const generatingTaskType = useCreateStore(s => s.statusInfo?.generatingTaskType);
  const statusCode = useCreateStore(s => s.statusInfo?.statusCode);

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
  const [collapsedVideoEpisodes, setCollapsedVideoEpisodes] = useState<Set<number>>(new Set());
  // 提示词模态框
  const [promptModalPanelKey, setPromptModalPanelKey] = useState<string | null>(null);
  const [promptModalTab, setPromptModalTab] = useState<'view' | 'edit'>('view');
  const [promptText, setPromptText] = useState('');
  const [promptLoading, setPromptLoading] = useState(false);
  const [promptEnhancing, setPromptEnhancing] = useState(false);
  const [batchEnhancingEpisodeId, setBatchEnhancingEpisodeId] = useState<number | null>(null);
  // generatingScript 持久化到 sessionStorage，刷新后恢复
  const [generatingScript, setGeneratingScript] = useState<number | null>(() => {
    const saved = projectId && sessionStorage.getItem(`gen_script_${projectId}`);
    return saved ? (saved === '-1' ? -1 : Number(saved)) : null;
  });
  const [lightboxUrl, setLightboxUrl] = useState<string | null>(null);


  /** 逐页生成中的 { "episodeId-pageIndex": true } */
  const [generatingPageKeys, setGeneratingPageKeys] = useState<Set<string>>(new Set());
  // 按 episodeId 索引的逐页生成 Set，避免渲染时 IIFE 创建新引用破坏 memo
  const generatingPagesByEpisode = useMemo(() => {
    const map = new Map<number, Set<number>>();
    generatingPageKeys.forEach(k => {
      const dashIdx = k.indexOf('-');
      const eid = Number(k.substring(0, dashIdx));
      const pidx = Number(k.substring(dashIdx + 1));
      if (!map.has(eid)) map.set(eid, new Set());
      map.get(eid)!.add(pidx);
    });
    return map;
  }, [generatingPageKeys]);
  const [generatingVideoKeys, setGeneratingVideoKeys] = useState<Set<string>>(new Set()); // "episodeId-panelId"
  const [approvingEpisodeId, setApprovingEpisodeId] = useState<number | null>(null);
  const [rejectingEpisodeId, setRejectingEpisodeId] = useState<number | null>(null);
  const [isBatchTtsLoading, setIsBatchTtsLoading] = useState(false);
  const [isBatchMergeLoading, setIsBatchMergeLoading] = useState(false);
  // Track which chapter/project batch scope is active (e.g., "enhance-ch-1", "tts-project")
  const [activeBatchScope, setActiveBatchScope] = useState<string | null>(null);
  // 已完成剧集的展开状态
  const [expandedPassedEpisodeId, setExpandedPassedEpisodeId] = useState<number | null>(null);
  const hasInitialLoadRef = useRef(false);

  // 持久化 generatingScript 到 sessionStorage
  useEffect(() => {
    if (!projectId) return;
    if (generatingScript !== null) {
      sessionStorage.setItem(`gen_script_${projectId}`, String(generatingScript));
    } else {
      sessionStorage.removeItem(`gen_script_${projectId}`);
    }
  }, [projectId, generatingScript]);

  // ==================== Script Polling ====================
  const scriptPollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  type LoadEpisodesFn = (options?: { silent?: boolean }) => Promise<void>;
  const loadEpisodesRef = useRef<LoadEpisodesFn>(async () => {});

  const startScriptPolling = useCallback(() => {
    if (scriptPollingRef.current) return;
    scriptPollingRef.current = setInterval(() => {
      void loadEpisodesRef.current({ silent: true });
    }, 5000);
  }, []);

  const stopScriptPolling = useCallback(() => {
    if (scriptPollingRef.current) {
      clearInterval(scriptPollingRef.current);
      scriptPollingRef.current = null;
    }
  }, []);

  useEffect(() => {
    return () => stopScriptPolling();
  }, [stopScriptPolling]);

  // Off-peak mode
  const [offPeak, setOffPeak] = useState(() => localStorage.getItem('video_off_peak') === 'true');
  const toggleOffPeak = useCallback(() => {
    setOffPeak(prev => {
      const next = !prev;
      localStorage.setItem('video_off_peak', String(next));
      return next;
    });
  }, []);

  // 视频提供商状态（支持 Vidu / Grok）
  const [videoProvider, setVideoProvider] = useState<string>(() =>
    (project?.projectInfo?.videoProvider as string) || 'vidu'
  );
  const isVidu = videoProvider.toLowerCase() === 'vidu';
  const isGrok = videoProvider.toLowerCase() === 'grok';
  const isKling = videoProvider.toLowerCase() === 'kling';

  // 同步 videoProvider prop 变化（其他端或 SSE 推送更新后）
  useEffect(() => {
    if (project?.projectInfo?.videoProvider) {
      setVideoProvider(project.projectInfo.videoProvider as string);
    }
  }, [project?.projectInfo?.videoProvider]);

  // 同步 videoModel：当 provider 切换到 kling 时，确保 model 是有效的 kling 模型
  useEffect(() => {
    if (isKling && videoModel !== 'kling-v3-omni-std' && videoModel !== 'kling-v3-omni-pro') {
      setVideoModel('kling-v3-omni-std');
    }
  }, [isKling]);

  // 切换视频提供商（先调 API，成功后再更新 UI 状态）
  // 注意：updateProject 是 PATCH 接口，支持部分字段更新
  const handleVideoProviderChange = useCallback(async (provider: string) => {
    if (!projectId) {
      setVideoProvider(provider);
      if (provider === 'kling') setVideoModel('kling-v3-omni-std');
      return;
    }
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const updates: any = { videoProvider: provider };
      if (provider === 'kling') {
        updates.videoModel = 'kling-v3-omni-std';
        setVideoModel('kling-v3-omni-std');
      }
      await updateProject(projectId, updates);
      setVideoProvider(provider);
    } catch (err) {
      console.error('更新视频提供商失败:', err);
      alert('更新视频提供商失败，请重试');
    }
  }, [projectId]);

  // 图片提供商状态（支持 Seedream / Nanobanana 切换）
  const [imageProvider, setImageProvider] = useState<string>(
    (project?.projectInfo?.imageProvider as string) || 'seedream'
  );
  useEffect(() => {
    if (project?.projectInfo?.imageProvider) {
      setImageProvider(project.projectInfo.imageProvider as string);
    }
  }, [project?.projectInfo?.imageProvider]);
  const handleImageProviderChange = useCallback(async (provider: string) => {
    if (!projectId) { setImageProvider(provider); return; }
    try {
      await updateProject(projectId, { imageProvider: provider } as any);
      setImageProvider(provider);
    } catch (err) {
      console.error('更新图片提供商失败:', err);
    }
  }, [projectId]);

  // Video model toggle (pro/mix/q3/turbo for Vidu, kling-v3-omni-std/kling-v3-omni-pro for Kling)
  const [videoModel, setVideoModel] = useState<'pro' | 'mix' | 'q3' | 'turbo' | 'kling-v3-omni-std' | 'kling-v3-omni-pro'>(() =>
    (localStorage.getItem('video_model') as 'pro' | 'mix' | 'q3' | 'turbo' | 'kling-v3-omni-std' | 'kling-v3-omni-pro') || 'turbo'
  );
  const toggleVideoModel = useCallback(() => {
    setVideoModel(prev => {
      const next = prev === 'pro' ? 'turbo' : 'pro';
      localStorage.setItem('video_model', next);
      return next;
    });
  }, []);

  // Refs
  const loadEpisodesControllerRef = useRef<AbortController | null>(null);
  // Guard: prevent concurrent refreshProductionStatuses calls for same episode
  const refreshInFlightRef = useRef<Set<number>>(new Set());
  const panelsLoadedRef = useRef<Set<number>>(new Set());
  // 跟踪 generatingScript 最新值，供 loadEpisodes 闭包内使用（避免加到依赖数组）
  const generatingScriptRef = useRef(generatingScript);
  generatingScriptRef.current = generatingScript;
  const generateVideoAbortRef = useRef<AbortController | null>(null);
  // 滚动位置恢复：防止 chapters 更新时列表跳回顶部
  const tabContentScrollRef = useRef<number>(0);

  useEffect(() => {
    return () => {
      generateVideoAbortRef.current?.abort();
    };
  }, []);

  // ==================== Data Loading ====================

  const loadEpisodes = useCallback(async (options?: { silent?: boolean }) => {
    if (!projectId) return;
    const silent = options?.silent ?? false;
    const showGlobalLoading = !silent && !hasInitialLoadRef.current;
    if (showGlobalLoading) {
      setLoading(true);
    }
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

              // Build synopsis: prefer sceneDescription, fallback to visualDescription, then scene, then scene_summary
              const synopsis = shot.sceneDescription
                || shot.visualDescription || shot.sceneSummary || shot.scene || '';

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
            shotSegments: textSegments,
            gridStatus: ep.episodeInfo?.gridStatus,
            gridImages: ep.episodeInfo?.gridImages || [],
            splitShots: ep.episodeInfo?.splitShots || [],
            gridRejectionFeedback: ep.episodeInfo?.gridRejectionFeedback || null,
            gridPromptHint: ep.episodeInfo?.gridPromptHint || '',
            gridPrompt: ep.episodeInfo?.gridPrompt || '',
            gridPrompts: ep.episodeInfo?.gridPrompts || [],
            gridConfigs: ep.episodeInfo?.gridConfigs,
            gridPageStatuses: ep.episodeInfo?.gridPageStatuses || [],
            gridPageErrors: ep.episodeInfo?.gridPageErrors || [],
            characterReferences: ep.episodeInfo?.characterReferences,
            panelApproved: ep.episodeInfo?.panelApproved ?? false,
            isNewFlow: !!ep.episodeInfo?.gridStatus,
            scriptStatus: ep.episodeInfo?.scriptStatus || 'pending',
            storyboardStatus: ep.episodeInfo?.storyboardStatus || 'pending',
            episodeInfo: ep.episodeInfo,
          };
        });

        builtChapters.push({ chapterIndex, title: chapterTitle, episodes: episodeStates });
      }
      setChapters(builtChapters);
      hasInitialLoadRef.current = true;

      // 如果 generatingScript 指向的 episode 已经生成完毕，自动清除（防止 SSE 丢失导致卡住）
      const currentGenScript = generatingScriptRef.current;
      if (currentGenScript != null) {
        const targetEp = builtChapters
          .flatMap(ch => ch.episodes)
          .find(ep => ep.episodeId === currentGenScript);
        if (targetEp && targetEp.storyboardStatus === 'done') {
          setGeneratingScript(null);
          stopScriptPolling();
        }
      }

      // Load panels for all episodes
      panelsLoadedRef.current.clear();
      const allIds = builtChapters.flatMap(ch => ch.episodes.map(ep => ep.episodeId));
      allIds.forEach(eid => { loadPanelsForEpisode(eid); refreshProductionStatuses(eid); });
    } catch (err: any) {
      setError(err?.message || '加载失败');
    } finally {
      if (showGlobalLoading) {
        setLoading(false);
      }
      loadEpisodesControllerRef.current = null;
    }
  }, [projectId]);

  useEffect(() => {
    loadEpisodesRef.current = loadEpisodes;
  }, [loadEpisodes]);

  useEffect(() => { loadEpisodes(); }, [loadEpisodes]);

  // 恢复视频生成状态：刷新页面后如果后端还在生成，自动恢复 generatingVideoKeys
  // 使用合并策略：保留本地已标记的生成中 key，只在后端确认完成/失败时才移除
  useEffect(() => {
    if (chapters.length === 0) return;
    setGeneratingVideoKeys(prev => {
      const next = new Set(prev);
      for (const ch of chapters) {
        for (const ep of ch.episodes) {
          for (const seg of ep.segments) {
            const key = `${ep.episodeId}-${seg.panelData?.panelId}`;
            if (seg.pipelineStep === 'video_generating') {
              next.add(key);
            } else if (seg.pipelineStep === 'video_completed' || seg.pipelineStep === 'video_failed') {
              next.delete(key);
            }
          }
        }
      }
      return next;
    });
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

  // 滚动位置恢复：chapters 更新（非初始加载）后恢复滚动位置
  const isInitialMount = useRef<boolean>(true);
  useEffect(() => {
    if (isInitialMount.current) {
      isInitialMount.current = false;
      return;
    }
    // 非初始挂载，chapters 有变化，恢复滚动
    requestAnimationFrame(() => {
      const el = document.querySelector('[class*="tabContent"]');
      if (el && tabContentScrollRef.current > 0) {
        el.scrollTop = tabContentScrollRef.current;
      }
    });
  }, [chapters]);

  // 仅在脚本阶段任务进行中启用脚本轮询，避免视频/TTS阶段触发全量刷新导致闪烁
  useEffect(() => {
    const shouldPollScript = isGenerating && generatingTaskType === 'episode';
    if (shouldPollScript) {
      startScriptPolling();
    } else {
      stopScriptPolling();
    }
  }, [isGenerating, generatingTaskType, startScriptPolling, stopScriptPolling]);

  // ==================== Panel Loading ====================

  const loadPanelsForEpisode = useCallback(async (episodeId: number) => {
    if (!projectId || panelsLoadedRef.current.has(episodeId)) return;
    panelsLoadedRef.current.add(episodeId);
    try {
      const res = await getPanels(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const panels = res.data || [];

      // If no panels exist yet (script stage), don't overwrite
      if (panels.length === 0) return;

      // Build panel-level segments (original behavior, used by 4C for batch video)
      const panelSegments: SegmentState[] = panels.map((panel: any, idx: number) => {
        const info = panel.panelInfo || {};
        const shots = info.shots || [];
        const isGroupedPanel = shots.length > 1;
        const synopsis = isGroupedPanel
          ? shots.map((s: any) => {
              const speaker = s.speaker && s.speaker !== '无' ? `【${s.speaker}】` : '';
              const desc = s.visualDescription || s.scene || '';
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

      // Build a map from splitShotStartIndex → panel index for shot→panel lookup
      const panelStartMap = new Map<number, { panelIdx: number; panel: any }>();
      panels.forEach((panel: any, panelIdx: number) => {
        const info = panel.panelInfo || {};
        const startIdx = info.splitShotStartIndex ?? 0;
        const shotCount = info.totalShots ?? (info.shots?.length ?? 0);
        for (let i = 0; i < shotCount; i++) {
          panelStartMap.set(startIdx + i, { panelIdx, panel });
        }
      });

      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep => {
          if (ep.episodeId !== episodeId) return ep;
          // Update shotSegments: merge panel data into existing shot-level segments (for 4A)
          const updatedShotSegments = (ep.shotSegments || ep.segments).map((seg, segIdx) => {
            const mapping = panelStartMap.get(segIdx);
            if (!mapping) return seg;
            const { panel } = mapping;
            const info = panel.panelInfo || {};
            return {
              ...seg,
              panelData: {
                ...seg.panelData,
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
                  : seg.panelData?.dialogue || '',
                characters: info.characters || seg.panelData?.characters || [],
                background: info.background || {},
                imagePromptHint: info.image_prompt_hint,
                sfx: info.sfx || [],
                duration: info.duration || info.totalDuration || seg.panelData?.duration,
                totalShots: info.totalShots,
                totalDuration: info.totalDuration || seg.panelData?.totalDuration,
                visualStyle: info.visualStyle || seg.panelData?.visualStyle,
              },
            };
          });
          return { ...ep, segments: panelSegments, shotSegments: updatedShotSegments };
        }),
      })));
    } catch (err) {
      console.error(`加载集 ${episodeId} 分镜失败:`, err);
    }
  }, [projectId]);

  const refreshProductionStatuses = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    // Guard: skip if already refreshing this episode
    if (refreshInFlightRef.current.has(episodeId)) return;
    refreshInFlightRef.current.add(episodeId);
    try {
      const res = await getBatchProductionStatuses(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const statusMap = new Map<number, any>();
      res.data.forEach((s: any) => statusMap.set(s.panelId, s));

      setChapters(prev => {
        // 快速检测：该 episode 是否有实际变化
        let hasChanges = false;
        for (const ch of prev) {
          for (const ep of ch.episodes) {
            if (ep.episodeId !== episodeId) continue;
            for (const seg of ep.segments) {
              const panelId = Number(seg.panelData?.panelId);
              const status = statusMap.get(panelId);
              if (!status) continue;
              const newStep = mapToPipelineStep(status.gridStatus, status.videoStatus);
              if (newStep !== seg.pipelineStep) { hasChanges = true; break; }
              if (status.videoProgress != null && status.videoProgress !== seg.videoProgress) { hasChanges = true; break; }
              if (status.videoStatus === 'completed' && status.videoUrl && status.videoUrl !== seg.videoUrl) { hasChanges = true; break; }
              if (status.ttsStatus === 'completed' && status.ttsAudioUrl && status.ttsAudioUrl !== seg.ttsAudioUrl) { hasChanges = true; break; }
              // Normalize null vs undefined for gridStatus/mergeStatus
              const apiGridStatus = status.gridStatus ?? null;
              const localGridStatus = seg.gridStatus ?? null;
              if (apiGridStatus !== localGridStatus) { hasChanges = true; break; }
              const apiMergeStatus = status.mergeStatus ?? null;
              const localMergeStatus = seg.mergeStatus ?? null;
              if (apiMergeStatus !== localMergeStatus) { hasChanges = true; break; }
              if (status.videoWithNarrationUrl && status.videoWithNarrationUrl !== seg.videoWithNarrationUrl) { hasChanges = true; break; }
            }
            if (hasChanges) break;
          }
          if (hasChanges) break;
        }
        if (!hasChanges) return prev; // 无变化，跳过 state 更新

        return prev.map(ch => ({
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
                      videoModel: status.videoModel ?? seg.videoModel,
                      videoOffPeak: status.offPeak ?? seg.videoOffPeak,
                      videoProgress: status.videoProgress != null ? status.videoProgress : seg.videoProgress,
                      videoCredits: status.videoCredits ?? seg.videoCredits,
                      ttsAudioUrl: status.ttsAudioUrl || seg.ttsAudioUrl,
                      ttsStatus: status.ttsStatus === 'completed' ? 'completed' : (status.ttsStatus || seg.ttsStatus),
                      ttsCredits: status.ttsCredits ?? seg.ttsCredits,
                      videoWithNarrationUrl: status.videoWithNarrationUrl || seg.videoWithNarrationUrl,
                      mergeStatus: status.mergeStatus === 'completed' ? 'completed' : (status.mergeStatus || seg.mergeStatus),
                      videoRefMode: status.videoRefMode ?? seg.videoRefMode,
                      referenceImages: status.referenceImages || seg.referenceImages,
                      referenceImageLabels: status.referenceImageLabels || seg.referenceImageLabels,
                    };
                  }),
                }
              : ep
          ),
        }));
      });
    } catch (err) {
      console.error('刷新生产状态失败:', err);
    } finally {
      refreshInFlightRef.current.delete(episodeId);
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
      // Page 2 剧本完成事件，4A 阶段不使用
    },
    onEpisodeStoryboardDone: (data) => {
      // 分镜生成完成 → 清除 generatingScript，停轮询
      setGeneratingScript(null);
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === data.episodeId
            ? { ...ep, storyboardStatus: 'done' as const }
            : ep
        ),
      })));
      if (data.episodeId) {
        panelsLoadedRef.current.delete(data.episodeId);
        loadPanelsForEpisode(data.episodeId);
        refreshProductionStatuses(data.episodeId);
      }
      void loadEpisodes();
    },
    onEpisodePanelDone: (data) => {
      // 重新加载 panels 数据（loadPanelsForEpisode 内部会 setChapters）
      if (data.episodeId) {
        panelsLoadedRef.current.delete(data.episodeId);
        loadPanelsForEpisode(data.episodeId);
      }
    },
    onEpisodeGridStatus: async (data) => {
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
        // 获取完整的 episode 数据以更新 gridImages 和 gridPrompt
        if (projectId) {
          try {
            const res = await getEpisodes(projectId);
            if (res.data?.items) {
              const updatedEp = res.data.items.find((e: any) => e.id === data.episodeId);
              if (updatedEp) {
                setChapters(prev => prev.map(ch => ({
                  ...ch,
                  episodes: ch.episodes.map(ep =>
                    ep.episodeId === data.episodeId
                      ? {
                          ...ep,
                          gridImages: updatedEp.episodeInfo?.gridImages || [],
                          splitShots: updatedEp.episodeInfo?.splitShots || [],
                          gridPromptHint: updatedEp.episodeInfo?.gridPromptHint || '',
                          gridRejectionFeedback: updatedEp.episodeInfo?.gridRejectionFeedback || null,
                          gridPrompt: updatedEp.episodeInfo?.gridPrompt || '',
                          gridPrompts: updatedEp.episodeInfo?.gridPrompts || [],
                          characterReferences: updatedEp.episodeInfo?.characterReferences,
                          gridPageStatuses: updatedEp.episodeInfo?.gridPageStatuses || ep.gridPageStatuses || [],
                          gridPageErrors: updatedEp.episodeInfo?.gridPageErrors || ep.gridPageErrors || [],
                          episodeInfo: updatedEp.episodeInfo,
                        }
                      : ep
                  ),
                })));
              }
            }
          } catch (err) {
            console.error('获取 episode 数据失败:', err);
          }
        }
        loadPanelsForEpisode(data.episodeId);
        refreshProductionStatuses(data.episodeId);
      }
    },
    onPanelVideoDone: (data) => {
      if (data.episodeId) {
        refreshProductionStatuses(data.episodeId);
        const key = `${data.episodeId}-${data.panelId}`;
        setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
      }
    },
    onPanelVideoFailed: (data) => {
      if (data.episodeId) {
        refreshProductionStatuses(data.episodeId);
        const key = `${data.episodeId}-${data.panelId}`;
        setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
      }
    },
    onPanelTtsDone: (data) => {
      if (data.episodeId) refreshProductionStatuses(data.episodeId);
    },
    onPanelTtsFailed: (data) => {
      if (data.episodeId) refreshProductionStatuses(data.episodeId);
    },
    onPanelMergeDone: (data) => {
      if (data.episodeId) refreshProductionStatuses(data.episodeId);
    },
    onPanelMergeFailed: (data) => {
      if (data.episodeId) refreshProductionStatuses(data.episodeId);
    },
    onStatusChange: (data) => {
      // task-complete 或 completed 时停止脚本轮询，并刷新全量数据
      if ((data as any).eventType === 'task-complete' || data.to === 'completed') {
        setGeneratingScript(null);
        setGeneratingGrid(null);
        stopScriptPolling();
        // 刷新全量 episodes 数据，确保 UI 显示最新状态
        void loadEpisodes();
      }
    },
    onReconnect: () => {
      // 重连时不需要任何操作，SSE 会推送最新状态
    },
  });

  // ==================== Tab Unlock Logic ====================

  const allEpisodes = useMemo(() => chapters.flatMap(ch => ch.episodes), [chapters]);

  // Filter chapters' episodes by pipeline stage
  const filterChaptersByStage = useCallback((stage: PipelineStage, chapters: ChapterState[]): ChapterState[] => {
    return chapters.map(ch => ({
      ...ch,
      episodes: ch.episodes.filter(ep => getPipelineStage(ep) === stage),
    })).filter(ch => ch.episodes.length > 0);
  }, []);

  // Per-tab episode counts for progress indicator
  const scriptCount = allEpisodes.filter(ep => getPipelineStage(ep) === 'script').length;
  const gridCount = allEpisodes.filter(ep => getPipelineStage(ep) === 'grid').length;
  const videoCount = allEpisodes.filter(ep => getPipelineStage(ep) === 'video').length;

  // ==================== Actions ====================

  // Generate script per episode (single episode generation)
  const handleGenerateScript = useCallback(async (episodeId: number) => {
    if (!projectId || generatingScript) return;
    setGeneratingScript(episodeId); // specific episode generating
    try {
      await generateEpisodeScripts(projectId, episodeId);
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
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '审核失败');
    } finally {
      setApprovingEpisodeId(null);
    }
  }, [projectId, loadEpisodes, approvingEpisodeId]);

  // Generate grid per episode: only generate pending/failed pages, skip already generated
  // 超时安全清理：只移除已到达终态（generated/failed）的 key，不碰仍在进行中的
  const clearTerminalKeys = useCallback(async (pageKeys: string[], pageIndices: number[]) => {
    try {
      const res = await getEpisodes(projectId!);
      for (const ep of (res.data?.items || [])) {
        const ps: string[] = ep.episodeInfo?.gridPageStatuses || [];
        const terminalKeys = pageKeys.filter((_, i) => {
          const pi = pageIndices[i];
          return ps[pi] === 'generated' || ps[pi] === 'failed';
        });
        if (terminalKeys.length > 0) {
          setGeneratingPageKeys(prev => { const n = new Set(prev); terminalKeys.forEach(k => n.delete(k)); return n; });
        }
      }
    } catch {}
    loadEpisodes();
  }, [projectId, loadEpisodes]);

  const handleGenerateGrid = useCallback(async (episodeId: number, fullPrompt?: string, gridPrompts?: string[]) => {
    if (!projectId) return;

    // 1. 获取当前 episode 的 gridPageStatuses 来决定哪些页需要生成
    const targetEp = chapters.flatMap(c => c.episodes).find(e => e.episodeId === episodeId);
    const currentPageStatuses = targetEp?.gridPageStatuses || [];

    // 如果完全没有状态数据（首次生成），走 reset 流程
    const hasAnyGenerated = currentPageStatuses.some(s => s === 'generated');
    if (!hasAnyGenerated && currentPageStatuses.length === 0) {
      // 首次生成：调 reset 端点初始化，然后逐页生成
      let totalPages: number;
      try {
        const res = await regenerateEpisodeGrid(projectId, episodeId, fullPrompt, gridPrompts);
        totalPages = res.data?.data ?? 1;
      } catch (err: any) {
        alert(err?.response?.data?.message || err?.message || '重置九宫格失败');
        return;
      }
      const pageKeys: string[] = [];
      for (let i = 0; i < totalPages; i++) pageKeys.push(`${episodeId}-${i}`);
      setGeneratingPageKeys(prev => { const n = new Set(prev); pageKeys.forEach(k => n.add(k)); return n; });

      const MAX_CONCURRENT = 2;
      let nextIdx = 0;
      let activeCount = 0;
      const tryLaunchNext = () => {
        while (activeCount < MAX_CONCURRENT && nextIdx < totalPages) {
          const idx = nextIdx++;
          activeCount++;
          regenerateEpisodeGridPage(projectId, episodeId, idx, gridPrompts?.[idx])
            .catch(() => {})
            .finally(() => { activeCount--; tryLaunchNext(); if (nextIdx >= totalPages && activeCount === 0) void pollAllDone(totalPages, pageKeys); });
        }
      };
      tryLaunchNext();

      const pollAllDone = async (tp: number, pk: string[]) => {
        for (let i = 0; i < 72; i++) {
          await new Promise(r => setTimeout(r, 5000));
          try {
            const res = await getEpisodes(projectId);
            const ep = (res.data?.items || []).find((e: any) => e.id === episodeId);
            if (!ep) continue;
            const ps: string[] = ep.episodeInfo?.gridPageStatuses || [];
            if (ps.length >= tp && ps.slice(0, tp).every((s: string) => s === 'generated' || s === 'failed')) {
              setGeneratingPageKeys(prev => { const n = new Set(prev); pk.forEach(k => n.delete(k)); return n; });
              loadEpisodes();
              return;
            }
          } catch {}
        }
        // 超时：只清理已到达终态的 key，不清用户后续手动触发的
        clearTerminalKeys(pk, Array.from({ length: tp }, (_, i) => i));
      };
      return;
    }

    // 2. 已有部分页完成：只生成 pending/failed 的页
    const pagesToGenerate: number[] = [];
    for (let i = 0; i < currentPageStatuses.length; i++) {
      if (currentPageStatuses[i] !== 'generated') {
        pagesToGenerate.push(i);
      }
    }
    if (pagesToGenerate.length === 0) return; // 全部已完成

    const pageKeys = pagesToGenerate.map(i => `${episodeId}-${i}`);
    setGeneratingPageKeys(prev => { const n = new Set(prev); pageKeys.forEach(k => n.add(k)); return n; });

    const MAX_CONCURRENT = 2;
    let nextIdx = 0;
    let activeCount = 0;
    const tryLaunchNext = () => {
      while (activeCount < MAX_CONCURRENT && nextIdx < pagesToGenerate.length) {
        const idx = pagesToGenerate[nextIdx++];
        activeCount++;
        regenerateEpisodeGridPage(projectId, episodeId, idx, gridPrompts?.[idx])
          .catch(() => {})
          .finally(() => { activeCount--; tryLaunchNext(); if (nextIdx >= pagesToGenerate.length && activeCount === 0) void pollRemaining(pageKeys); });
      }
    };
    tryLaunchNext();

    const pollRemaining = async (pk: string[]) => {
      for (let i = 0; i < 72; i++) {
        await new Promise(r => setTimeout(r, 5000));
        try {
          const res = await getEpisodes(projectId);
          const ep = (res.data?.items || []).find((e: any) => e.id === episodeId);
          if (!ep) continue;
          const ps: string[] = ep.episodeInfo?.gridPageStatuses || [];
          const allTerminal = pagesToGenerate.every(pi => {
            const s = ps[pi];
            return s === 'generated' || s === 'failed';
          });
          if (allTerminal) {
            setGeneratingPageKeys(prev => { const n = new Set(prev); pk.forEach(k => n.delete(k)); return n; });
            loadEpisodes();
            return;
          }
        } catch {}
      }
      // 超时：只清理已到达终态的 key
      clearTerminalKeys(pk, pagesToGenerate);
    };
  }, [projectId, chapters, loadEpisodes]);

  // Per-page grid generation
  const pagePollCancelledRef = useRef<Set<string>>(new Set());
  const handleGenerateGridPage = useCallback(async (episodeId: number, pageIndex: number, prompt?: string) => {
    if (!projectId) return;
    const key = `${episodeId}-${pageIndex}`;
    // 取消同一页之前的轮询
    pagePollCancelledRef.current.add(key);
    const myGenVersion = `${key}-${Date.now()}`;
    pagePollCancelledRef.current.delete(myGenVersion);

    setGeneratingPageKeys(prev => new Set(prev).add(key));
    let genVersion: string | undefined;
    try {
      const res = await regenerateEpisodeGridPage(projectId, episodeId, pageIndex, prompt);
      genVersion = res.data?.data?.genVersion;
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '生成宫格图失败');
      setGeneratingPageKeys(prev => { const n = new Set(prev); n.delete(key); return n; });
      return;
    }
    // Poll until page is done (with cancellation and genVersion check)
    const pollPage = async () => {
      for (let i = 0; i < 60; i++) {
        await new Promise(r => setTimeout(r, 5000));
        if (pagePollCancelledRef.current.has(myGenVersion)) return;
        const res = await getEpisodes(projectId);
        if (pagePollCancelledRef.current.has(myGenVersion)) return;
        const ep = (res.data?.items || []).find((e: any) => e.id === episodeId);
        if (!ep) continue;
        // 优先检查 per-page 状态（终态立即停止）
        const pageStatuses: string[] = ep.episodeInfo?.gridPageStatuses || [];
        const pageStatus = pageStatuses[pageIndex];
        if (pageStatus === 'generated' || pageStatus === 'failed') {
          setGeneratingPageKeys(prev => { const n = new Set(prev); n.delete(key); return n; });
          loadEpisodes();
          return;
        }
        // 兼容旧数据：无 gridPageStatuses 时回退检查 gridImages
        if (!pageStatuses.length) {
          const pageVersion = ep.episodeInfo?.['gridGenPageVersion_' + pageIndex];
          if (genVersion && pageVersion !== genVersion) continue;
          const gridImages = ep.episodeInfo?.gridImages;
          if (gridImages && gridImages[pageIndex]) {
            setGeneratingPageKeys(prev => { const n = new Set(prev); n.delete(key); return n; });
            loadEpisodes();
            return;
          }
        }
      }
      setGeneratingPageKeys(prev => { const n = new Set(prev); n.delete(key); return n; });
      loadEpisodes();
    };
    void pollPage();
  }, [projectId, loadEpisodes]);

  // Approve/reject grid per episode
  const handleApproveGrid = useCallback(async (episodeId: number) => {
    if (!projectId || approvingEpisodeId) return;
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

  // Reject to script stage (4b → 4a rollback)
  const handleRejectToScript = useCallback(async (episodeId: number) => {
    if (!projectId || rejectingEpisodeId) return;
    setRejectingEpisodeId(episodeId);
    try {
      await rejectToScript(projectId, episodeId);
      panelsLoadedRef.current.delete(episodeId);
      await loadEpisodes();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '退回脚本阶段失败');
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

    // 立即设置本地状态为生成中，确保进度条和 UI 立即反映
    setChapters(prev => prev.map(ch => ({
      ...ch,
      episodes: ch.episodes.map(ep =>
        ep.episodeId === episodeId
          ? {
              ...ep,
              segments: ep.segments.map(seg =>
                seg.panelData?.panelId === panelId
                  ? { ...seg, pipelineStep: 'video_generating' as const, videoProgress: 0, videoStatus: 'generating' as any }
                  : seg
              ),
            }
          : ep
      ),
    })));

    generateVideo(projectId, episodeId, Number(panelId), offPeak, customPrompt, isVidu || isKling ? videoModel : undefined)
      .catch((err: any) => {
        abort.abort();
        alert(err?.response?.data?.message || err?.message || '生成视频失败');
        setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
      });
  }, [projectId, offPeak, refreshProductionStatuses, videoModel, isKling]);

  const isVideoRefMode = project?.projectInfo?.videoRefMode === true;
  const [refVideoModel, setRefVideoModel] = useState<string>(() =>
    (project?.projectInfo?.videoModel as string) || 'viduq3-mix'
  );
  const isMixModel = refVideoModel.includes('mix');

  // Generate video per panel (reference image mode - 参考图视频)
  const handleGenerateVideoRef = useCallback(async (episodeId: number, panelId: string, customPrompt?: string) => {
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

    // 立即设置本地状态为生成中，确保进度条和 UI 立即反映
    setChapters(prev => prev.map(ch => ({
      ...ch,
      episodes: ch.episodes.map(ep =>
        ep.episodeId === episodeId
          ? {
              ...ep,
              segments: ep.segments.map(seg =>
                seg.panelData?.panelId === panelId
                  ? { ...seg, pipelineStep: 'video_generating' as const, videoProgress: 0, videoStatus: 'generating' as any }
                  : seg
              ),
            }
          : ep
      ),
    })));

    // viduq3-mix 强制 offPeak=false
    const effectiveOffPeak = isMixModel ? false : offPeak;
    generateVideoRef(projectId, episodeId, Number(panelId), effectiveOffPeak, customPrompt, refVideoModel || undefined)
      .catch((err: any) => {
        abort.abort();
        alert(err?.response?.data?.message || err?.message || '生成视频失败');
        setGeneratingVideoKeys(prev => { const next = new Set(prev); next.delete(key); return next; });
      });
  }, [projectId, offPeak, refreshProductionStatuses, isMixModel, refVideoModel]);

  // Batch generate videos for an episode
  const handleBatchGenerateVideo = useCallback(async (episodeId: number) => {
    const episode = chapters.flatMap(ch => ch.episodes).find(ep => ep.episodeId === episodeId);
    if (!episode) return;
    for (const seg of episode.segments) {
      const panelId = seg.panelData?.panelId;
      const key = `${episodeId}-${panelId}`;
      if (panelId && !seg.videoUrl && !generatingVideoKeys.has(key)) {
        if (isVideoRefMode) {
          await handleGenerateVideoRef(episodeId, panelId);
        } else {
          await handleGenerateVideo(episodeId, panelId);
        }
      }
    }
  }, [chapters, handleGenerateVideo, handleGenerateVideoRef, isVideoRefMode, generatingVideoKeys]);

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
      if (!panelId || seg.videoUrl || generatingVideoKeys.has(`${episodeId}-${panelId}`)) continue;
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

  // ==================== Chapter / Project Level Batch Handlers ====================

  const videoStageChapters = useMemo(() => filterChaptersByStage('video', chapters), [chapters]);

  // Batch generate videos for all episodes in a chapter
  const handleChapterBatchVideo = useCallback((chapterIndex: number) => {
    const chapter = videoStageChapters.find(ch => ch.chapterIndex === chapterIndex);
    if (!chapter) return;
    chapter.episodes.forEach(ep => handleBatchGenerateVideo(ep.episodeId));
  }, [videoStageChapters, handleBatchGenerateVideo]);

  // Batch generate videos for all video-stage episodes
  const handleProjectBatchVideo = useCallback(() => {
    videoStageChapters.forEach(ch => {
      ch.episodes.forEach(ep => handleBatchGenerateVideo(ep.episodeId));
    });
  }, [videoStageChapters, handleBatchGenerateVideo]);

  // Batch enhance prompts for all episodes in a chapter
  const handleChapterBatchEnhance = useCallback(async (chapterIndex: number) => {
    if (activeBatchScope) return;
    const chapter = videoStageChapters.find(ch => ch.chapterIndex === chapterIndex);
    if (!chapter) return;
    setActiveBatchScope(`enhance-ch-${chapterIndex}`);
    let totalSuccess = 0;
    let totalFailed = 0;
    for (const ep of chapter.episodes) {
      for (const seg of ep.segments) {
        const panelId = seg.panelData?.panelId;
        if (!panelId || seg.videoUrl || generatingVideoKeys.has(`${ep.episodeId}-${panelId}`)) continue;
        try {
          await enhanceVideoPromptApi(projectId, ep.episodeId, Number(panelId));
          totalSuccess++;
        } catch {
          totalFailed++;
        }
      }
    }
    setActiveBatchScope(null);
    if (totalFailed === 0) {
      alert(`第${chapterIndex}章已润色 ${totalSuccess} 个分组的提示词`);
    } else {
      alert(`第${chapterIndex}章润色完成：${totalSuccess} 成功，${totalFailed} 失败`);
    }
  }, [activeBatchScope, videoStageChapters, projectId]);

  // Batch enhance prompts for all video-stage episodes
  const handleProjectBatchEnhance = useCallback(async () => {
    if (activeBatchScope) return;
    setActiveBatchScope('enhance-project');
    let totalSuccess = 0;
    let totalFailed = 0;
    for (const ch of videoStageChapters) {
      for (const ep of ch.episodes) {
        for (const seg of ep.segments) {
          const panelId = seg.panelData?.panelId;
          if (!panelId || seg.videoUrl) continue;
          try {
            await enhanceVideoPromptApi(projectId, ep.episodeId, Number(panelId));
            totalSuccess++;
          } catch {
            totalFailed++;
          }
        }
      }
    }
    setActiveBatchScope(null);
    if (totalFailed === 0) {
      alert(`全部已润色 ${totalSuccess} 个分组的提示词`);
    } else {
      alert(`全部润色完成：${totalSuccess} 成功，${totalFailed} 失败`);
    }
  }, [activeBatchScope, videoStageChapters, projectId]);

  // Batch generate TTS for all episodes in a chapter
  const handleChapterBatchTts = useCallback(async (chapterIndex: number) => {
    if (!projectId || activeBatchScope) return;
    const chapter = videoStageChapters.find(ch => ch.chapterIndex === chapterIndex);
    if (!chapter) return;
    setActiveBatchScope(`tts-ch-${chapterIndex}`);
    for (const ep of chapter.episodes) {
      await batchGenerateTts(projectId, ep.episodeId);
    }
    setActiveBatchScope(null);
  }, [projectId, activeBatchScope, videoStageChapters]);

  // Batch generate TTS for all video-stage episodes
  const handleProjectBatchTts = useCallback(async () => {
    if (!projectId || activeBatchScope) return;
    setActiveBatchScope('tts-project');
    for (const ch of videoStageChapters) {
      for (const ep of ch.episodes) {
        await batchGenerateTts(projectId, ep.episodeId);
      }
    }
    setActiveBatchScope(null);
  }, [projectId, activeBatchScope, videoStageChapters]);

  // Batch merge audio for all episodes in a chapter
  const handleChapterBatchMerge = useCallback(async (chapterIndex: number) => {
    if (!projectId || activeBatchScope) return;
    const chapter = videoStageChapters.find(ch => ch.chapterIndex === chapterIndex);
    if (!chapter) return;
    setActiveBatchScope(`merge-ch-${chapterIndex}`);
    for (const ep of chapter.episodes) {
      await batchMergeAudio(projectId, ep.episodeId);
    }
    setActiveBatchScope(null);
  }, [projectId, activeBatchScope, videoStageChapters]);

  // Batch merge audio for all video-stage episodes
  const handleProjectBatchMerge = useCallback(async () => {
    if (!projectId || activeBatchScope) return;
    setActiveBatchScope('merge-project');
    for (const ch of videoStageChapters) {
      for (const ep of ch.episodes) {
        await batchMergeAudio(projectId, ep.episodeId);
      }
    }
    setActiveBatchScope(null);
  }, [projectId, activeBatchScope, videoStageChapters]);

  // Confirm all panels done -> advance to Step 5
  const [advancing, setAdvancing] = useState(false);
  const handleConfirmPanels = useCallback(async () => {
    if (!projectId || advancing) return;
    const confirmed = window.confirm('确认所有分镜视频无误后，将进入视频合成阶段。是否继续？');
    if (!confirmed) return;
    // 已完成的项目重新走 Step4 时，直接导航到 Step5，不调用状态推进（COMPLETED 状态无法再触发 confirm_panels）
    if (statusCode === 'completed') {
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
  }, [projectId, advancing, onNextStep, statusCode]);

  // Generate TTS for a single panel
  const handleGenerateTts = useCallback(async (episodeId: number, panelId: string) => {
    if (!projectId) return;
    // Optimistically update local state to 'generating'
    setChapters(prev => prev.map(ch => ({
      ...ch,
      episodes: ch.episodes.map(ep =>
        ep.episodeId === episodeId
          ? {
              ...ep,
              segments: ep.segments.map(seg =>
                seg.panelData?.panelId === panelId
                  ? { ...seg, ttsStatus: 'generating' as const }
                  : seg
              ),
            }
          : ep
      ),
    })));
    try {
      await generatePanelTts(projectId, episodeId, Number(panelId));
    } catch (err: any) {
      // Revert to failed on error
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === episodeId
            ? {
                ...ep,
                segments: ep.segments.map(seg =>
                  seg.panelData?.panelId === panelId
                    ? { ...seg, ttsStatus: 'failed' as const }
                    : seg
                ),
              }
            : ep
        ),
      })));
      alert(err?.response?.data?.message || err?.message || '旁白生成失败');
    }
  }, [projectId]);

  // Batch generate TTS for an entire episode
  const handleBatchGenerateTts = useCallback(async (episodeId: number) => {
    if (!projectId || isBatchTtsLoading) return;
    setIsBatchTtsLoading(true);
    try {
      await batchGenerateTts(projectId, episodeId);
      // After batch request is accepted, set all pending segments to generating
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === episodeId
            ? {
                ...ep,
                segments: ep.segments.map(seg =>
                  !seg.ttsAudioUrl && seg.ttsStatus !== 'generating'
                    ? { ...seg, ttsStatus: 'generating' as const }
                    : seg
                ),
              }
            : ep
        ),
      })));
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '批量旁白生成失败');
    } finally {
      setIsBatchTtsLoading(false);
    }
  }, [projectId, isBatchTtsLoading]);

  // Merge audio for a single panel
  const handleMergeAudio = useCallback(async (episodeId: number, panelId: string) => {
    if (!projectId) return;
    // Optimistically update local state to 'generating'
    setChapters(prev => prev.map(ch => ({
      ...ch,
      episodes: ch.episodes.map(ep =>
        ep.episodeId === episodeId
          ? {
              ...ep,
              segments: ep.segments.map(seg =>
                seg.panelData?.panelId === panelId
                  ? { ...seg, mergeStatus: 'generating' as const }
                  : seg
              ),
            }
          : ep
      ),
    })));
    try {
      await mergePanelAudio(projectId, episodeId, Number(panelId));
      // 刷新状态以获取 mergeStatus=completed 和 videoWithNarrationUrl
      refreshProductionStatuses(episodeId);
    } catch (err: any) {
      // Revert to failed on error
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === episodeId
            ? {
                ...ep,
                segments: ep.segments.map(seg =>
                  seg.panelData?.panelId === panelId
                    ? { ...seg, mergeStatus: 'failed' as const }
                    : seg
                ),
              }
            : ep
        ),
      })));
      alert(err?.response?.data?.message || err?.message || '合成旁白视频失败');
    }
  }, [projectId]);

  // Batch merge audio for an entire episode
  const handleBatchMergeAudio = useCallback(async (episodeId: number) => {
    if (!projectId || isBatchMergeLoading) return;
    setIsBatchMergeLoading(true);
    try {
      await batchMergeAudio(projectId, episodeId);
      // After batch request is accepted, set all completed-TTS segments without merge to generating
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeId === episodeId
            ? {
                ...ep,
                segments: ep.segments.map(seg =>
                  (seg.ttsStatus === 'completed' || !!seg.ttsAudioUrl) && (seg.pipelineStep === 'video_completed' || !!seg.videoUrl) && seg.mergeStatus !== 'completed'
                    ? { ...seg, mergeStatus: 'generating' as const }
                    : seg
                ),
              }
            : ep
        ),
      })));
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '批量合成旁白视频失败');
    } finally {
      setIsBatchMergeLoading(false);
    }
  }, [projectId, isBatchMergeLoading]);

  // 打开提示词模态框（稳定的 useCallback，避免子组件不必要重渲染）
  const openPromptModal = useCallback(async (episodeId: number, panelId: string) => {
    const panelKey = `${episodeId}-${panelId}`;
    setPromptModalPanelKey(panelKey);
    setPromptModalTab('view');
    setPromptText('');
    setPromptLoading(true);
    try {
      const res = await getVideoPrompt(projectId!, episodeId, Number(panelId));
      setPromptText(res.data?.prompt || '');
    } catch {
      setPromptText('');
    } finally {
      setPromptLoading(false);
    }
  }, [projectId]);

  const handleRetryLoad = useCallback(() => {
    void loadEpisodes();
  }, [loadEpisodes]);

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

  const refreshEpisode = useCallback(async (_episodeId: number) => {
    await loadEpisodes();
  }, [loadEpisodes]);

  // Stats
  const totalPanels = allEpisodes.reduce((sum, ep) => sum + ep.segments.length, 0);
  const completedVideos = allEpisodes.reduce((sum, ep) =>
    sum + ep.segments.filter(seg => seg.pipelineStep === 'video_completed').length, 0);
  const allSegments = allEpisodes.flatMap(ep => ep.segments);
  const mergeTotalCount = allSegments.filter(s => (s.ttsStatus === 'completed' || !!s.ttsAudioUrl) && (s.pipelineStep === 'video_completed' || !!s.videoUrl)).length;
  const isComicCommentary = project?.projectInfo?.productionMode === 'comic_commentary';
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
          <button onClick={handleRetryLoad} className={styles.retryButton}>重试</button>
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
        {/* 图片提供商选择 */}
        <div className={styles.headerImageProviderSelector}>
          <span className={styles.headerVideoModelLabel}>图片：</span>
          <div className={styles.headerVideoProviderTabs}>
            <button
              className={`${styles.headerVideoProviderTab} ${imageProvider === 'seedream' ? styles.headerVideoProviderTabActive : ''}`}
              onClick={() => handleImageProviderChange('seedream')}
            >Seedream</button>
            <button
              className={`${styles.headerVideoProviderTab} ${imageProvider === 'nanobanana' ? styles.headerVideoProviderTabActive : ''}`}
              onClick={() => handleImageProviderChange('nanobanana')}
            >Nanobanana</button>
          </div>
        </div>
        {/* 视频提供商 + 模型选择器 */}
        <div className={styles.headerVideoModelSelector}>
          {/* 视频提供商选择（参考图视频仅支持 Vidu，隐藏 Grok） */}
          <div className={styles.headerVideoProviderTabs}>
            <button
              className={`${styles.headerVideoProviderTab} ${isVidu ? styles.headerVideoProviderTabActive : ''}`}
              onClick={() => handleVideoProviderChange('vidu')}
            >
              Vidu
            </button>
            {!isVideoRefMode && (
              <button
                className={`${styles.headerVideoProviderTab} ${isGrok ? styles.headerVideoProviderTabActive : ''}`}
                onClick={() => handleVideoProviderChange('grok')}
              >
                Grok
              </button>
            )}
            {!isVideoRefMode && (
              <button
                className={`${styles.headerVideoProviderTab} ${isKling ? styles.headerVideoProviderTabActive : ''}`}
                onClick={() => handleVideoProviderChange('kling')}
              >
                Kling
              </button>
            )}
          </div>
          {/* Vidu 模型选择 */}
          {isVidu && !isVideoRefMode && (
            <div className={styles.headerVideoModelTabs}>
              <span className={styles.headerVideoModelLabel}>模型：</span>
              <button
                className={`${styles.headerVideoModelTab} ${videoModel === 'pro' ? styles.headerVideoModelTabActive : ''}`}
                onClick={() => setVideoModel('pro')}
              >
                Pro
              </button>
              <button
                className={`${styles.headerVideoModelTab} ${videoModel === 'mix' ? styles.headerVideoModelTabActive : ''}`}
                onClick={() => setVideoModel('mix')}
              >
                Mix
              </button>
              <button
                className={`${styles.headerVideoModelTab} ${videoModel === 'q3' ? styles.headerVideoModelTabActive : ''}`}
                onClick={() => setVideoModel('q3')}
              >
                Q3
              </button>
              <button
                className={`${styles.headerVideoModelTab} ${videoModel === 'turbo' ? styles.headerVideoModelTabActive : ''}`}
                onClick={() => setVideoModel('turbo')}
              >
                Turbo
              </button>
            </div>
          )}
          {isVidu && isVideoRefMode && (
            <div className={styles.headerVideoModelTabs}>
              <span className={styles.headerVideoModelLabel}>模型：</span>
              <button
                className={`${styles.headerVideoModelTab} ${refVideoModel === 'viduq3-mix' ? styles.headerVideoModelTabActive : ''}`}
                onClick={async () => {
                  if (refVideoModel === 'viduq3-mix') return;
                  setRefVideoModel('viduq3-mix');
                  if (projectId) await updateProject(projectId, { videoModel: 'viduq3-mix' } as any).catch(console.error);
                }}
              >
                Q3 Mix
              </button>
              <button
                className={`${styles.headerVideoModelTab} ${refVideoModel === 'viduq3' ? styles.headerVideoModelTabActive : ''}`}
                onClick={async () => {
                  if (refVideoModel === 'viduq3') return;
                  setRefVideoModel('viduq3');
                  if (projectId) await updateProject(projectId, { videoModel: 'viduq3' } as any).catch(console.error);
                }}
              >
                Q3
              </button>
              <button
                className={`${styles.headerVideoModelTab} ${refVideoModel === 'viduq3-turbo' ? styles.headerVideoModelTabActive : ''}`}
                onClick={async () => {
                  if (refVideoModel === 'viduq3-turbo') return;
                  setRefVideoModel('viduq3-turbo');
                  if (projectId) await updateProject(projectId, { videoModel: 'viduq3-turbo' } as any).catch(console.error);
                }}
              >
                Q3 Turbo
              </button>
            </div>
          )}
          {isKling && (
            <div className={styles.headerVideoModelTabs}>
              <span className={styles.headerVideoModelLabel}>模式：</span>
              <button
                className={`${styles.headerVideoModelTab} ${videoModel === 'kling-v3-omni-std' ? styles.headerVideoModelTabActive : ''}`}
                onClick={async () => {
                  setVideoModel('kling-v3-omni-std');
                  if (projectId) await updateProject(projectId, { videoModel: 'kling-v3-omni-std' } as any);
                }}
              >
                Std
              </button>
              <button
                className={`${styles.headerVideoModelTab} ${videoModel === 'kling-v3-omni-pro' ? styles.headerVideoModelTabActive : ''}`}
                onClick={async () => {
                  setVideoModel('kling-v3-omni-pro');
                  if (projectId) await updateProject(projectId, { videoModel: 'kling-v3-omni-pro' } as any);
                }}
              >
                Pro
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Step Progress Bar */}
      <div className={styles.stepProgress}>
        {STEPS.map((step, idx) => {
          const isActive = activeTab === step.key;
          const count = step.key === 'script' ? scriptCount
            : step.key === 'grid' ? gridCount
            : videoCount;
          // Tab is "completed" when no episodes remain at this stage AND at least one episode has progressed beyond it
          const completed = count === 0 && allEpisodes.length > 0
            && (step.key === 'script' ? (gridCount + videoCount > 0)
              : step.key === 'grid' ? videoCount > 0
              : false);
          const stepClasses = [
            styles.stepItem,
            isActive && styles.stepItemActive,
            completed && !isActive && styles.stepItemCompleted,
          ].filter(Boolean).join(' ');

          return (
            <div key={step.key} style={{ display: 'contents' }}>
              <button
                className={stepClasses}
                onClick={() => switchTab(step.key)}
              >
                <span className={styles.stepNumber}>
                  {completed && !isActive ? <CheckIcon /> : `4${step.number}`}
                </span>
                <span className={styles.stepLabel}>
                  {step.label}
                  {count > 0 && <span className={styles.stepCount}>{count}</span>}
                </span>
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
          <div className={styles.batchBarActions}>
            <button
              className={styles.btnGhost}
              onClick={handleProjectBatchVideo}
              disabled={generatingVideoKeys.size > 0 || completedVideos === totalPanels}
            >
              全部批量生成
            </button>
            {!isVideoRefMode && (
            <button
              className={styles.btnGhost}
              onClick={handleProjectBatchEnhance}
              disabled={!!activeBatchScope || batchEnhancingEpisodeId !== null}
            >
              {activeBatchScope === 'enhance-project' ? <><SpinIcon /> 润色中...</> : '全部润色'}
            </button>
            )}
            {isComicCommentary && (
            <button
              className={styles.btnGhost}
              onClick={handleProjectBatchTts}
              disabled={!!activeBatchScope || isBatchTtsLoading}
            >
              {activeBatchScope === 'tts-project' ? <><SpinIcon /> 生成中...</> : `全部旁白`}
            </button>
            )}
            {isComicCommentary && (
              <button
                className={styles.btnGhost}
                onClick={handleProjectBatchMerge}
                disabled={!!activeBatchScope || isBatchMergeLoading || mergeTotalCount === 0}
              >
                {activeBatchScope === 'merge-project' ? <><SpinIcon /> 合成中...</> : '全部合成'}
              </button>
            )}
          </div>
          <div className={styles.toolbarToggles}>
            {!isKling && !(isVideoRefMode && isMixModel) && (
            <button
              className={`${styles.offPeakToggle} ${offPeak ? styles.offPeakActive : ''}`}
              onClick={toggleOffPeak}
            >
              <span className={styles.toggleTrack}><span className={styles.toggleThumb} /></span>
              <span className={styles.toggleLabel}>错峰</span>
            </button>
            )}
            {isVidu && !isVideoRefMode && (
              <button
                className={styles.modelToggle}
                onClick={toggleVideoModel}
              >
                <span className={styles.modelToggleTrack}>
                  <span className={styles.modelToggleThumb} style={{ left: videoModel === 'pro' ? '2px' : 'auto', right: videoModel === 'turbo' ? '2px' : 'auto' }} />
                </span>
                <span className={styles.toggleLabel}>{videoModel === 'pro' ? 'Pro' : 'Turbo'}</span>
              </button>
            )}
          </div>
        </div>
      )}

      {/* Tab Content */}
      <div
        className={styles.tabContent}
        onScroll={e => { tabContentScrollRef.current = (e.target as HTMLElement).scrollTop; }}
      >

        {/* ==================== Tab 4a: Script ==================== */}
        {activeTab === 'script' && (
          <div className={styles.scriptReviewSection}>
            {chapters.length === 0 ? (
              <div className={styles.emptyState}><p>暂无章节数据</p></div>
            ) : (
              chapters.map(chapter => {
                const doneEps = new Set(chapter.episodes.filter((ep: EpisodeState) => ep.panelApproved && ep.gridStatus !== 'approved').map(ep => ep.episodeId));
                const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
                const doneCount = doneEps.size;
                return (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <div className={styles.chapterHeader}>
                    <button className={styles.chapterHeaderLeft} onClick={() => toggleChapter(chapter.chapterIndex)}>
                      <span className={styles.chapterNumberBadge}>
                        {String(chapter.chapterIndex).padStart(2, '0')}
                      </span>
                      <div className={styles.chapterInfo}>
                        <h2 className={styles.chapterTitle}>{chapter.title.replace(/^#+\s*/, '')}</h2>
                      </div>
                      {doneCount > 0 && (
                        <span className={styles.chapterDoneBadge}>
                          <span className={styles.chapterDoneBadgeDot} />
                          {doneCount} 集已完成
                        </span>
                      )}
                      <ChevronIcon open={chapterOpen} />
                    </button>
                  </div>
                  {chapterOpen && (
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => {
                      const isDone = doneEps.has(ep.episodeId);
                      if (isDone) {
                        return (
                          <DoneEpisodeCard
                            key={ep.episodeId}
                            episode={{ ...ep, segments: ep.shotSegments || ep.segments }}
                            project={project}
                            expandedPassedEpisodeId={expandedPassedEpisodeId}
                            onToggleExpanded={setExpandedPassedEpisodeId}
                            buildGridPromptText={buildGridPromptText}
                            buildMultiShotPromptText={buildMultiShotPromptText}
                            nextStageLabel="→ 九宫格"
                            showScriptContent={true}
                            showGridPrompt={true}
                            showGridImages={true}
                            onOpenLightbox={setLightboxUrl}
                          />
                        );
                      }
                      return (
                        <ScriptEpisodeCard
                          key={ep.episodeId}
                          episode={{ ...ep, segments: ep.shotSegments || ep.segments }}
                          projectId={projectId!}
                          generatingScript={generatingScript}
                          approvingEpisodeId={approvingEpisodeId}
                          expandedEpisodeId={expandedEpisodeId}
                          onGenerateScript={handleGenerateScript}
                          onApproveScript={handleApproveScript}
                          onToggleEpisode={toggleEpisode}
                          onRefreshEpisode={refreshEpisode}
                        />
                      );
                    })}
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
                const doneEps = new Set(chapter.episodes.filter((ep: EpisodeState) => ep.panelApproved && ep.gridStatus === 'approved').map(ep => ep.episodeId));
                const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
                const doneCount = doneEps.size;
                return (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <div className={styles.chapterHeader}>
                    <button className={styles.chapterHeaderLeft} onClick={() => toggleChapter(chapter.chapterIndex)}>
                      <span className={styles.chapterNumberBadge}>
                        {String(chapter.chapterIndex).padStart(2, '0')}
                      </span>
                      <div className={styles.chapterInfo}>
                        <h2 className={styles.chapterTitle}>{chapter.title.replace(/^#+\s*/, '')}</h2>
                      </div>
                      {doneCount > 0 && (
                        <span className={styles.chapterDoneBadge}>
                          <span className={styles.chapterDoneBadgeDot} />
                          {doneCount} 集已完成
                        </span>
                      )}
                      <ChevronIcon open={chapterOpen} />
                    </button>
                  </div>
                  {chapterOpen && (
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => (
                      <GridEpisodeCard
                        key={ep.episodeId}
                        projectId={projectId}
                        episode={ep}
                        generatingPages={generatingPagesByEpisode.get(ep.episodeId) || new Set()}
                        approvingEpisodeId={approvingEpisodeId}
                        rejectingEpisodeId={rejectingEpisodeId}
                        onGenerateGrid={handleGenerateGrid}
                        onGenerateGridPage={handleGenerateGridPage}
                        onApproveGrid={handleApproveGrid}
                        onRejectGrid={handleRejectGrid}
                        onRejectToScript={handleRejectToScript}
                        onOpenLightbox={setLightboxUrl}
                        buildGridPromptText={buildGridPromptText}
                        gridPageStatuses={ep.gridPageStatuses}
                        gridPageErrors={ep.gridPageErrors}
                      />
                    ))}
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
              filterChaptersByStage('video', chapters).map(chapter => {
                const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
                const chDoneCount = chapter.episodes.reduce((sum, ep) =>
                  sum + ep.segments.filter(s => s.videoUrl || s.pipelineStep === 'video_completed').length, 0);
                const chTotalCount = chapter.episodes.reduce((sum, ep) => sum + ep.segments.length, 0);
                const chAllDone = chTotalCount > 0 && chDoneCount === chTotalCount;
                const chScope = (op: string) => `${op}-ch-${chapter.chapterIndex}`;
                return (
                <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                  <div className={styles.chapterHeader}>
                    <button className={styles.chapterHeaderLeft} onClick={() => toggleChapter(chapter.chapterIndex)}>
                      <span className={styles.chapterNumberBadge}>
                        {String(chapter.chapterIndex).padStart(2, '0')}
                      </span>
                      <div className={styles.chapterInfo}>
                        <h2 className={styles.chapterTitle}>{chapter.title.replace(/^#+\s*/, '')}</h2>
                      </div>
                      <span className={styles.chapterProgress}>
                        <span className={styles.chapterProgressText}>{chDoneCount}/{chTotalCount}</span>
                      </span>
                      <ChevronIcon open={chapterOpen} />
                    </button>
                    <div className={styles.chapterBatchActions}>
                      <button
                        className={styles.btnGhost}
                        onClick={() => handleChapterBatchVideo(chapter.chapterIndex)}
                        disabled={chAllDone}
                      >
                        {chAllDone ? '已完成' : '批量生成'}
                      </button>
                      {!isVideoRefMode && (
                      <button
                        className={styles.btnGhost}
                        onClick={() => handleChapterBatchEnhance(chapter.chapterIndex)}
                        disabled={!!activeBatchScope || batchEnhancingEpisodeId !== null || chAllDone}
                      >
                        {activeBatchScope === chScope('enhance') ? <><SpinIcon /> 润色中...</> : '润色'}
                      </button>
                      )}
                      {isComicCommentary && (
                      <button
                        className={styles.btnGhost}
                        onClick={() => handleChapterBatchTts(chapter.chapterIndex)}
                        disabled={!!activeBatchScope || isBatchTtsLoading}
                      >
                        {activeBatchScope === chScope('tts') ? <><SpinIcon /> 生成中...</> : '旁白'}
                      </button>
                      )}
                      {isComicCommentary && (
                        <button
                          className={styles.btnGhost}
                          onClick={() => handleChapterBatchMerge(chapter.chapterIndex)}
                          disabled={!!activeBatchScope || isBatchMergeLoading}
                        >
                          {activeBatchScope === chScope('merge') ? <><SpinIcon /> 合成中...</> : '合成'}
                        </button>
                      )}
                    </div>
                  </div>
                  {chapterOpen && (
                  <div className={styles.episodeList}>
                    {chapter.episodes.map(ep => {
                      const doneCount = ep.segments.filter(s => s.videoUrl || s.pipelineStep === 'video_completed').length;
                      const allDone = ep.segments.length > 0 && doneCount === ep.segments.length;
                      const epTtsCompletedCount = ep.segments.filter(seg => seg.ttsStatus === 'completed' || !!seg.ttsAudioUrl).length;
                      const epTtsTotalCount = ep.segments.length;
                      const epMergeCompletedCount = ep.segments.filter(s => s.mergeStatus === 'completed').length;
                      const epMergeTotalCount = ep.segments.filter(s => (s.ttsStatus === 'completed' || !!s.ttsAudioUrl) && (s.pipelineStep === 'video_completed' || !!s.videoUrl)).length;
                      const epCollapsed = collapsedVideoEpisodes.has(ep.episodeId);
                      return (
                        <div key={ep.episodeId} className={styles.episodeVideoCard}>
                          <div className={styles.episodeVideoHeader} style={{ cursor: 'pointer' }} onClick={() => setCollapsedVideoEpisodes(prev => {
                            const next = new Set(prev);
                            next.has(ep.episodeId) ? next.delete(ep.episodeId) : next.add(ep.episodeId);
                            return next;
                          })}>
                            <div>
                              <h3 className={styles.episodeVideoTitle}>
                                第{ep.episodeIndex}集 {ep.title}
                              </h3>
                              <span className={styles.episodeVideoProgress}>
                                {doneCount} / {ep.segments.length} 分镜已完成
                              </span>
                            </div>
                            <div className={styles.episodeVideoHeaderActions} onClick={e => e.stopPropagation()}>
                              <button
                                className={allDone ? styles.btnGhost : styles.btnSuccess}
                                onClick={() => handleBatchGenerateVideo(ep.episodeId)}
                                disabled={allDone}
                              >
                                {allDone ? '已完成' : '批量生成'}
                              </button>
                              {!allDone && !isVideoRefMode && (
                                <button
                                  className={styles.btnGhost}
                                  onClick={() => handleBatchEnhance(ep.episodeId)}
                                  disabled={!!batchEnhancingEpisodeId}
                                >
                                  {batchEnhancingEpisodeId === ep.episodeId ? <><SpinIcon /> 润色中...</> : '批量润色'}
                                </button>
                              )}
                              {isComicCommentary && (
                              <button
                                className={styles.btnGhost}
                                onClick={() => handleBatchGenerateTts(ep.episodeId)}
                                disabled={isBatchTtsLoading}
                              >
                                {isBatchTtsLoading ? <><SpinIcon /> 生成中...</> : `批量旁白 (${epTtsCompletedCount}/${epTtsTotalCount})`}
                              </button>
                              )}
                              {isComicCommentary && (
                                <button
                                  className={styles.btnGhost}
                                  onClick={() => handleBatchMergeAudio(ep.episodeId)}
                                  disabled={isBatchMergeLoading || epMergeTotalCount === 0}
                                >
                                  {isBatchMergeLoading ? <><SpinIcon /> 合成中...</> : `一键合成旁白视频 (${epMergeCompletedCount}/${epMergeTotalCount})`}
                                </button>
                              )}
                            </div>
                            <span style={{ marginLeft: 8, color: 'var(--color-text-muted)', fontSize: 12, flexShrink: 0 }}>
                              {epCollapsed ? '▸' : '▾'}
                            </span>
                          </div>

                          {/* Panel video rows */}
                          {!epCollapsed && <div className={styles.panelVideoList}>
                            {ep.segments.map((seg, idx) => (
                              <VideoSegmentRow
                                key={idx}
                                episode={ep}
                                segment={seg}
                                segmentIndex={idx}
                                isComicCommentary={isComicCommentary}
                                isVideoRefMode={isVideoRefMode}
                                generatingVideoKeys={generatingVideoKeys}
                                expandedPanelKey={expandedPanelKey}
                                onGenerateVideo={isVideoRefMode ? handleGenerateVideoRef : handleGenerateVideo}
                                onGenerateTts={handleGenerateTts}
                                onMergeAudio={handleMergeAudio}
                                onTogglePanel={setExpandedPanelKey}
                                onOpenPromptModal={openPromptModal}
                                onOpenLightbox={setLightboxUrl}
                              />
                            ))}
                          </div>}
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
            const handler = isVideoRefMode ? handleGenerateVideoRef : handleGenerateVideo;
            handler(episodeId, panelId, promptModalTab === 'edit' ? promptText : undefined);
          };

          return (
            <div className={styles.modalOverlay} onClick={() => setPromptModalPanelKey(null)}>
              <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
                <div className={styles.modalHeader}>
                  <h3>{promptModalTab === 'edit' ? '编辑提示词' : '查看提示词'}</h3>
                  <button className={styles.modalClose} onClick={() => setPromptModalPanelKey(null)}>&times;</button>
                </div>

                {/* 融合参考图 */}
                {!isVideoRefMode && seg?.fusionImageUrl && (
                  <div className={styles.modalFusionWrap}>
                    <span className={styles.modalFusionLabel}>融合参考图</span>
                    <img src={seg.fusionImageUrl} alt="融合参考图" className={styles.modalFusionImg} />
                  </div>
                )}
                {isVideoRefMode && (
                  <div className={styles.modalFusionWrap}>
                    <span className={styles.modalFusionLabel}>参考图视频模式</span>
                    <span style={{ fontSize: 'var(--font-size-sm)', color: 'var(--color-text-muted)' }}>
                      使用分镜切分图 + 角色图作为参考，无需融合图
                    </span>
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
                      <div className={styles.modalPromptHeader}>
                        <div className={styles.modalPromptHint}>以下是 AI 根据分镜内容自动生成的提示词，用于视频生成</div>
                        <button
                          className={styles.modalEnhanceBtn}
                          onClick={handleEnhance}
                          disabled={promptEnhancing || promptLoading}
                        >
                          {promptEnhancing ? '优化中...' : 'AI 优化提示词（消耗1积分）'}
                        </button>
                      </div>
                      <pre className={styles.modalPromptPre}>{promptText || '(暂无提示词)'}</pre>
                    </div>
                  ) : (
                    <div className={styles.modalPromptEdit}>
                      <div className={styles.modalPromptHint}>你编辑的内容会作为最终 Prompt 直接下发给视频模型（覆盖增强版原文）。</div>
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

// ==================== DoneEpisodeCard: 已完成剧集的折叠预览卡片 ====================

interface DoneEpisodeCardProps {
  episode: EpisodeState;
  project: any;
  expandedPassedEpisodeId: number | null;
  onToggleExpanded: (episodeId: number | null) => void;
  buildGridPromptText?: (visualStyle: string, shots: any[], isComicCommentary?: boolean, gridCols?: number, gridRows?: number) => string;
  buildMultiShotPromptText?: (visualStyle: string, shots: any[], isComicCommentary?: boolean) => string;
  nextStageLabel: string;
  /** 是否显示脚本内容（4A 已完成时） */
  showScriptContent?: boolean;
  /** 是否显示九宫格提示词（4B 已完成时） */
  showGridPrompt?: boolean;
  /** 是否显示视频提示词（4C 已完成时） */
  showVideoPrompt?: boolean;
  /** 是否显示九宫格图片（4B 已完成时） */
  showGridImages?: boolean;
  /** 点击九宫格图片时打开大图预览 */
  onOpenLightbox?: (url: string) => void;
}

const DoneEpisodeCard = React.memo(function DoneEpisodeCard({
  episode,
  project,
  expandedPassedEpisodeId,
  onToggleExpanded,
  buildGridPromptText,
  buildMultiShotPromptText,
  nextStageLabel,
  showScriptContent = false,
  showGridPrompt = false,
  showVideoPrompt = false,
  showGridImages = false,
  onOpenLightbox,
}: DoneEpisodeCardProps) {
  const isExpanded = expandedPassedEpisodeId === episode.episodeId;
  const isComicCommentary = project?.projectInfo?.productionMode === 'comic_commentary';
  const allShots = episode.segments.map(s => s.shots?.[0]).filter(Boolean);
  const visualStyle = episode.segments[0]?.panelData?.visualStyle || 'ANIME';
  // 分页展示提示词和图片
  const [currentPage, setCurrentPage] = useState(1);
  const totalPages = episode.gridImages?.length || 1;
  // 获取真实保存的 prompt（优先 gridPrompts，其次 gridPrompt，最后计算）
  const getSavedPrompt = (pageIndex: number): string => {
    if (episode.gridPrompts && episode.gridPrompts.length > pageIndex) {
      return episode.gridPrompts[pageIndex];
    }
    if (pageIndex === 0 && episode.gridPrompt) {
      return episode.gridPrompt;
    }
    // fallback: 动态计算网格尺寸
    if (buildGridPromptText && allShots.length > 0) {
      const config = (episode as any).gridConfigs?.[pageIndex];
      let cols = 3, rows = 3;
      if (config) {
        cols = config.gridCols;
        rows = config.gridRows;
      } else {
        if (allShots.length <= 4) { cols = 2; rows = 2; }
      }
      const capacity = cols * rows;
      let fromIdx = 0;
      const configs = (episode as any).gridConfigs;
      if (configs && configs.length > pageIndex) {
        for (let i = 0; i < pageIndex; i++) {
          fromIdx += configs[i].shotCount ?? (configs[i].gridCols * configs[i].gridRows);
        }
      } else {
        fromIdx = pageIndex * capacity;
      }
      const toIdx = Math.min(fromIdx + capacity, allShots.length);
      const pageShots = allShots.slice(fromIdx, toIdx);
      return buildGridPromptText(visualStyle, pageShots, isComicCommentary, cols, rows);
    }
    return '';
  };

  return (
    <div className={`${styles.episodeScriptCard} ${styles.episodeCardDone}`}>
      <div
        className={styles.episodeScriptHeader}
        style={{ cursor: 'pointer' }}
        onClick={() => onToggleExpanded(isExpanded ? null : episode.episodeId)}
      >
        <div>
          <h3 className={styles.episodeScriptTitle}>
            第{episode.episodeIndex}集 {episode.title}
            <span className={styles.episodeCardDoneLabel}>✓ 已通过</span>
          </h3>
          <span className={styles.episodeScriptCount}>
            {episode.segments.length > 0 ? `${(episode as any).shotSegments?.length || episode.segments.length} 个分镜` : '暂无分镜数据'}
          </span>
        </div>
        <div className={styles.episodeCardExpand}>
          <span className={styles.episodeCardNextStage}>{nextStageLabel}</span>
          <span className={`${styles.episodeCardExpandIcon} ${isExpanded ? styles.episodeCardExpandIconOpen : ''}`}>
            {isExpanded ? '▾' : '▸'}
          </span>
        </div>
      </div>

      {isExpanded && (showScriptContent || showGridPrompt || showVideoPrompt || showGridImages) && (
        <div className={styles.episodeCardExpandedContent}>
          {/* 脚本内容 */}
          {showScriptContent && episode.segments.length > 0 && (
            <div className={styles.scriptSegmentList}>
              {episode.segments.map((seg, idx) => (
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
          {/* 九宫格：图片 + 提示词统一分页 */}
          {(showGridPrompt || showGridImages) && (episode.gridImages?.length || episode.gridPrompts?.length || episode.gridPrompt) && (
            <div className={styles.episodeGridReviewSection}>
              <div className={styles.episodeGridReviewHeader}>
                <span className={styles.episodeGridReviewTitle}>九宫格预览</span>
                {totalPages > 1 && (
                  <div className={styles.gridPageTabs}>
                    {Array.from({ length: totalPages }, (_, idx) => (
                      <button
                        key={idx}
                        className={`${styles.gridPageTab} ${currentPage === idx + 1 ? styles.gridPageTabActive : ''}`}
                        onClick={() => setCurrentPage(idx + 1)}
                      >
                        第{idx + 1}页
                      </button>
                    ))}
                  </div>
                )}
                <div
                  className={styles.episodePromptCollapse}
                  onClick={() => onToggleExpanded(isExpanded ? null : episode.episodeId)}
                >
                  收起 ▲
                </div>
              </div>
              {/* 左图右文布局 */}
              <div className={styles.episodeGridReviewBody}>
                {showGridImages && episode.gridImages?.[currentPage - 1] && (
                  <div className={styles.episodeGridReviewImage}>
                    <img
                      src={episode.gridImages[currentPage - 1]}
                      alt={`九宫格 第${currentPage}页`}
                      onClick={() => onOpenLightbox?.(episode.gridImages![currentPage - 1])}
                    />
                  </div>
                )}
                {showGridPrompt && (
                  <pre className={styles.episodeGridReviewPrompt}>
                    {getSavedPrompt(currentPage - 1) || '(暂无提示词)'}
                  </pre>
                )}
              </div>
              {(() => {
                if (!buildGridPromptText) return null;
                // 支持 gridConfigs 分页提示词
                const configs = (episode as any).gridConfigs;
                const pageCount = configs?.length || Math.ceil(allShots.length / 9) || 1;
                const pages: string[] = [];
                for (let pi = 0; pi < pageCount; pi++) {
                  if (episode.gridPrompts && episode.gridPrompts.length > pi) {
                    pages.push(episode.gridPrompts[pi]);
                  } else if (pi === 0 && episode.gridPrompt) {
                    pages.push(episode.gridPrompt);
                  } else {
                    const config = configs?.[pi];
                    const cols = config?.gridCols ?? 3;
                    const rows = config?.gridRows ?? 3;
                    const capacity = cols * rows;
                    let fromIdx = 0;
                    if (configs && configs.length > pi) {
                      for (let i = 0; i < pi; i++) {
                        fromIdx += configs[i].shotCount ?? (configs[i].gridCols * configs[i].gridRows);
                      }
                    } else {
                      fromIdx = pi * 9;
                    }
                    const toIdx = Math.min(fromIdx + capacity, allShots.length);
                    const pageShots = allShots.slice(fromIdx, toIdx);
                    pages.push(buildGridPromptText(visualStyle, pageShots, isComicCommentary, cols, rows));
                  }
                }
                // return pages.map((prompt, idx) => (
                //   <pre key={idx} className={styles.episodePromptBlock}>
                //     {pageCount > 1 && <div style={{ marginBottom: 8, color: 'var(--color-text-muted)', fontSize: 'var(--font-size-sm)' }}>第 {idx + 1} 页 / 共 {pageCount} 页</div>}
                //     {prompt}
                //   </pre>
                // ));
                return null;
              })()}
            </div>
          )}
          {/* 视频提示词 */}
          {showVideoPrompt && allShots.length > 0 && buildMultiShotPromptText && (
            <div className={styles.episodePromptPreview}>
              <button className={styles.episodePromptToggle}>
                视频生成 Prompt（多镜头）
              </button>
              <pre className={styles.episodePromptBlock}>
                {buildMultiShotPromptText(visualStyle, allShots, isComicCommentary)}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
});
