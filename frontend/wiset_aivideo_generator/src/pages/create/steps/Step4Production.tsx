/**
 * Step4Production - 分镜生产 Tab 子阶段
 * Tab 4a: 脚本生成/审核
 * Tab 4b: 九宫格图片生成/审核
 * Tab 4c: 视频生成/确认
 */
import { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import styles from './Step4Production.module.less';
import type { ChapterState, EpisodeState, PipelineStage, SegmentState } from './types';
import { getPipelineStage } from './types';
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
  generatePanelTts,
  batchGenerateTts,
  mergePanelAudio,
  batchMergeAudio,
  rejectToScript,
} from '../../../services/episodeService';
import { advanceStatus } from '../../../services/projectService';
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

const SpinIcon = () => <span className={styles.btnSpinner} />;

const STEPS = [
  { key: 'script' as SubPhase, number: 'a', label: '脚本生成' },
  { key: 'grid' as SubPhase, number: 'b', label: '九宫格图片' },
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
const buildGridPromptText = (visualStyle: string, shots: any[], isComicCommentary?: boolean): string => {
  const stylePrefix = STYLE_PREFIX_MAP[visualStyle] || '高质量，杰作级别，精细插画，柔光效果，色彩鲜艳。';
  const lines: string[] = [];

  lines.push(stylePrefix + '专业动画关键帧级别，电影级画面构图，精致光影与色彩。');
  lines.push('');

  lines.push('【布局要求 - 必须严格遵守】');
  lines.push('输出一张严格 3×3 九宫格分镜图，图片必须为横屏宽高比 16:9（宽大于高），严禁竖屏或正方形输出。');
  lines.push('图片必须被 2 条黑色竖线（约 4px 宽）和 2 条黑色横线（约 4px 宽）均匀分割为 3 行 3 列，共 9 个等大的格子。');
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

  lines.push('【分镜内容 - 按从左到右、从上到下填入九宫格，每个格子必须是精致的关键帧画面】');
  lines.push('每个分镜必须包含：完整的场景环境细节（光影、色调、空间纵深）、角色的精确外貌与服装、细腻的面部表情和肢体语言、精心设计的构图与景深关系。画面要有电影级质感。');
  lines.push('');

  shots.forEach((shot, i) => {
    const row = Math.floor(i / 3) + 1;
    const col = i % 3 + 1;
    let line = `第${row}行第${col}列: ${shot.visualDescription || shot.visual_description || ''}`;
    if (shot.shotSize) line += `，${shot.shotSize}`;
    if (shot.cameraAngle) line += `，${shot.cameraAngle}`;
    if (shot.cameraMovement) line += `，${shot.cameraMovement}`;
    if (shot.scene) line += `，场景: ${shot.scene}`;
    lines.push(line);
    if (isComicCommentary) {
      const nar = shot.narration || (shot.speaker === '旁白' ? shot.dialogue : '');
      if (nar && nar !== '无') lines.push(`  解说旁白(口播): ${nar}`);
    }
  });

  const emptySlots = 9 - shots.length;
  if (emptySlots > 0) {
    lines.push(`剩余 ${emptySlots} 个格子留空（纯黑色填充，不绘制任何内容）。`);
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
    lines.push(`Scene: ${shot.shotSize || ''}，${shot.cameraAngle || ''}，${shot.cameraMovement || ''}，${shot.visualDescription || shot.visual_description || ''}`);

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
  const [isBatchTtsLoading, setIsBatchTtsLoading] = useState(false);
  const [isBatchMergeLoading, setIsBatchMergeLoading] = useState(false);
  // Track which chapter/project batch scope is active (e.g., "enhance-ch-1", "tts-project")
  const [activeBatchScope, setActiveBatchScope] = useState<string | null>(null);
  // 已完成剧集的展开状态
  const [expandedPassedEpisodeId, setExpandedPassedEpisodeId] = useState<number | null>(null);
  const hasInitialLoadRef = useRef(false);

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

  // Video model toggle (pro/turbo), only for Vidu
  const isVidu = (project?.projectInfo?.videoProvider || '').toLowerCase() === 'vidu';
  const [videoModel, setVideoModel] = useState<'pro' | 'turbo'>(() =>
    (localStorage.getItem('video_model') as 'pro' | 'turbo') || 'turbo'
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
    // Guard: prevent concurrent calls
    if (loadEpisodesControllerRef.current) return;
    loadEpisodesControllerRef.current = new AbortController();
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
            gridPromptHint: ep.episodeInfo?.gridPromptHint || '',
            panelApproved: ep.episodeInfo?.panelApproved ?? false,
            isNewFlow: !!ep.episodeInfo?.gridStatus,
            scriptStatus: ep.episodeInfo?.scriptStatus || 'pending',
            storyboardStatus: ep.episodeInfo?.storyboardStatus || 'pending',
          };
        });

        builtChapters.push({ chapterIndex, title: chapterTitle, episodes: episodeStates });
      }
      setChapters(builtChapters);
      hasInitialLoadRef.current = true;

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
    setGeneratingVideoKeys(keys);
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

  // Restore generating UI state from backend after page refresh
  useEffect(() => {
    if (!isGenerating) {
      setGeneratingScript(null);
      setGeneratingGrid(null);
      // 不清除 generatingVideoKeys — 视频生成进度由 polling 独立管理
      return;
    }
    const taskType = generatingTaskType;
    // "episode" = script generation in progress
    if (taskType === 'episode') {
      setGeneratingScript(-1); // -1 = batch generating, unknown specific episode
      // Switch to script tab
      switchTab('script');
    }
    // "panel" = grid/video generation in progress
    if (taskType === 'panel') {
      const hasVideoGenerating = chapters.some(ch =>
        ch.episodes.some(ep => ep.segments.some(seg =>
          seg.pipelineStep === 'video_generating'
        ))
      );
      const hasGridGenerating = chapters.some(ch =>
        ch.episodes.some(ep => ep.segments.some(seg =>
          seg.pipelineStep === 'grid_generating'
        ))
      );
      if (hasVideoGenerating) {
        switchTab('video');
      } else if (hasGridGenerating) {
        switchTab('grid');
      }
    }
  }, [isGenerating, generatingTaskType, chapters]);

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
      // stage=stage1: script (Stage 1) 完成 → 精准更新 scriptStatus，不全量刷新
      setGeneratingScript(null);
      setChapters(prev => prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep =>
          ep.episodeIndex === data.episodeNum
            ? { ...ep, scriptStatus: 'done' as const }
            : ep
        ),
      })));
      // 重新加载 panels 数据
      const ep = chapters.flatMap(ch => ch.episodes).find(e => e.episodeIndex === data.episodeNum);
      if (ep) {
        panelsLoadedRef.current.delete(ep.episodeId);
        loadPanelsForEpisode(ep.episodeId);
      }
    },
    onEpisodeStoryboardDone: (data) => {
      // Stage 2 (storyboard) 完成 → 精准更新 storyboardStatus，不全量刷新
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
      // task-complete 或 completed 时停止脚本轮询
      if ((data as any).eventType === 'task-complete' || data.to === 'completed') {
        setGeneratingScript(null);
        setGeneratingGrid(null);
        stopScriptPolling();
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

  // Get episodes that have passed beyond a given stage (for collapsed footer)
  const getPassedEpisodes = useCallback((stage: PipelineStage, chapters: ChapterState[]): EpisodeState[] => {
    const stageOrder: PipelineStage[] = ['script', 'grid', 'video'];
    const currentIdx = stageOrder.indexOf(stage);
    return chapters.flatMap(ch => ch.episodes).filter(ep => {
      const epIdx = stageOrder.indexOf(getPipelineStage(ep));
      return epIdx > currentIdx;
    });
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
  const handleGenerateGrid = useCallback(async (episodeId: number, customHint?: string) => {
    if (!projectId || generatingGrid) return;
    setGeneratingGrid(episodeId);
    try {
      await regenerateEpisodeGrid(projectId, episodeId, customHint);
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

    generateVideo(projectId, episodeId, Number(panelId), offPeak, customPrompt, isVidu ? videoModel : undefined)
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
        if (!panelId || seg.videoUrl) continue;
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
      </div>

      {/* Step Progress Bar */}
      <div className={styles.stepProgress}>
        {STEPS.map((step, idx) => {
          const isActive = activeTab === step.key;
          const count = step.key === 'script' ? scriptCount
            : step.key === 'grid' ? gridCount
            : videoCount;
          // Tab is "completed" when no episodes remain at this stage
          const completed = count === 0 && allEpisodes.length > 0;
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
            <button
              className={styles.btnGhost}
              onClick={handleProjectBatchEnhance}
              disabled={!!activeBatchScope || batchEnhancingEpisodeId !== null}
            >
              {activeBatchScope === 'enhance-project' ? <><SpinIcon /> 润色中...</> : '全部润色'}
            </button>
            <button
              className={styles.btnGhost}
              onClick={handleProjectBatchTts}
              disabled={!!activeBatchScope || isBatchTtsLoading}
            >
              {activeBatchScope === 'tts-project' ? <><SpinIcon /> 生成中...</> : `全部旁白`}
            </button>
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
            <button
              className={`${styles.offPeakToggle} ${offPeak ? styles.offPeakActive : ''}`}
              onClick={toggleOffPeak}
            >
              <span className={styles.toggleTrack}><span className={styles.toggleThumb} /></span>
              <span className={styles.toggleLabel}>错峰</span>
            </button>
            {isVidu && (
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
              filterChaptersByStage('script', chapters).map(chapter => {
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
                      <ScriptEpisodeCard
                        key={ep.episodeId}
                        episode={ep}
                        project={project}
                        generatingScript={generatingScript}
                        approvingEpisodeId={approvingEpisodeId}
                        rejectingEpisodeId={rejectingEpisodeId}
                        expandedEpisodeId={expandedEpisodeId}
                        expandedPanelKey={expandedPanelKey}
                        onGenerateScript={handleGenerateScript}
                        onApproveScript={handleApproveScript}
                        onRejectScript={handleRejectScript}
                        onToggleEpisode={toggleEpisode}
                        onTogglePromptPreview={setExpandedPanelKey}
                        buildGridPromptText={buildGridPromptText}
                        buildMultiShotPromptText={buildMultiShotPromptText}
                      />
                    ))}
                  </div>
                  )}
                </div>
              )
              })
            )}

            {getPassedEpisodes('script', chapters).length > 0 && (
              <details className={styles.passedEpisodesSection}>
                <summary className={styles.passedEpisodesSummary}>
                  已完成脚本审核（{getPassedEpisodes('script', chapters).length} 集）- 点击展开查看脚本内容
                </summary>
                <div className={styles.passedEpisodesList}>
                  {getPassedEpisodes('script', chapters).map(ep => {
                    const stage = getPipelineStage(ep);
                    const stageLabel = stage === 'grid' ? '→ 九宫格' : '→ 视频';
                    const isExpanded = expandedPassedEpisodeId === ep.episodeId;
                    const isComicCommentary = project?.projectInfo?.productionMode === 'comic_commentary';
                    const allShots = ep.segments.map(s => s.shots?.[0]).filter(Boolean);
                    const visualStyle = ep.segments[0]?.panelData?.visualStyle || 'ANIME';
                    return (
                      <div key={ep.episodeId} className={styles.passedEpisodeItem}>
                        <div style={{ width: '100%' }}>
                          <div
                            style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', cursor: 'pointer' }}
                            onClick={() => setExpandedPassedEpisodeId(isExpanded ? null : ep.episodeId)}
                          >
                            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <span style={{ color: isExpanded ? '#1890ff' : 'var(--color-text-secondary)', fontSize: 14 }}>
                                {isExpanded ? '▾' : '▸'}
                              </span>
                              <span className={styles.passedEpisodeTitle}>第{ep.episodeIndex}集 {ep.title}</span>
                              <span style={{ fontSize: 12, color: '#999' }}>
                                {ep.segments.length} 个分镜
                              </span>
                            </div>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                              <span className={styles.passedEpisodeStage}>{stageLabel}</span>
                            </div>
                          </div>
                          {isExpanded && allShots.length > 0 && (
                            <div style={{ marginTop: 12, borderTop: '1px solid var(--color-border)', paddingTop: 10 }}>
                              <div className={styles.episodePromptPreview}>
                                <button
                                  className={styles.episodePromptToggle}
                                  style={{ marginBottom: 4 }}
                                  onClick={(e) => { e.stopPropagation(); setExpandedPassedEpisodeId(isExpanded ? null : ep.episodeId); }}
                                >
                                  图片生成 Prompt（九宫格）
                                </button>
                                <pre className={styles.episodePromptBlock} style={{ maxHeight: 200, overflow: 'auto' }}>
                                  {buildGridPromptText(visualStyle, allShots, isComicCommentary)}
                                </pre>
                                <button
                                  className={styles.episodePromptToggle}
                                  style={{ marginTop: 10, marginBottom: 4 }}
                                  onClick={(e) => { e.stopPropagation(); setExpandedPassedEpisodeId(isExpanded ? null : ep.episodeId); }}
                                >
                                  视频生成 Prompt（多镜头）
                                </button>
                                <pre className={styles.episodePromptBlock} style={{ maxHeight: 200, overflow: 'auto' }}>
                                  {buildMultiShotPromptText(visualStyle, allShots, isComicCommentary)}
                                </pre>
                              </div>
                            </div>
                          )}
                          {isExpanded && allShots.length === 0 && (
                            <div style={{ marginTop: 8, color: '#999', fontSize: 12 }}>
                              暂无分镜数据
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </details>
            )}
          </div>
        )}

        {/* ==================== Tab 4b: Grid ==================== */}
        {activeTab === 'grid' && (
          <div className={styles.gridReviewSection}>
            {chapters.length === 0 ? (
              <div className={styles.emptyState}><p>暂无章节数据</p></div>
            ) : (
              filterChaptersByStage('grid', chapters).map(chapter => {
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
                      <GridEpisodeCard
                        key={ep.episodeId}
                        episode={ep}
                        generatingGrid={generatingGrid}
                        approvingEpisodeId={approvingEpisodeId}
                        rejectingEpisodeId={rejectingEpisodeId}
                        onGenerateGrid={handleGenerateGrid}
                        onApproveGrid={handleApproveGrid}
                        onRejectGrid={handleRejectGrid}
                        onRejectToScript={handleRejectToScript}
                        onOpenLightbox={setLightboxUrl}
                      />
                    ))}
                  </div>
                  )}
                </div>
              )
              })
            )}

            {getPassedEpisodes('grid', chapters).length > 0 && (
              <details className={styles.passedEpisodesSection}>
                <summary className={styles.passedEpisodesSummary}>
                  已完成九宫格审核（{getPassedEpisodes('grid', chapters).length} 集）- 点击展开查看九宫格图片
                </summary>
                <div className={styles.passedEpisodesList}>
                  {getPassedEpisodes('grid', chapters).map(ep => {
                    const isExpanded = expandedPassedEpisodeId === ep.episodeId;
                    return (
                      <div key={ep.episodeId} className={styles.passedEpisodeItem}>
                        <div style={{ width: '100%' }}>
                          <div
                            style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', cursor: 'pointer' }}
                            onClick={() => setExpandedPassedEpisodeId(isExpanded ? null : ep.episodeId)}
                          >
                            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <span style={{ color: isExpanded ? '#1890ff' : 'var(--color-text-secondary)', fontSize: 14 }}>
                                {isExpanded ? '▾' : '▸'}
                              </span>
                              <span className={styles.passedEpisodeTitle}>第{ep.episodeIndex}集 {ep.title}</span>
                              <span style={{ fontSize: 12, color: '#999' }}>
                                {ep.gridImages?.length || 0} 张九宫格
                              </span>
                            </div>
                            <span className={styles.passedEpisodeStage}>→ 视频</span>
                          </div>
                          {isExpanded && ep.gridImages && ep.gridImages.length > 0 && (
                            <div style={{ marginTop: 12, borderTop: '1px solid var(--color-border)', paddingTop: 10 }}>
                              <div className={styles.gridImagesContainer}>
                                {ep.gridImages.map((url, idx) => (
                                  <img
                                    key={idx}
                                    src={url}
                                    alt={`九宫格 ${idx + 1}`}
                                    className={styles.gridImage}
                                    style={{ cursor: 'pointer' }}
                                    onClick={() => setLightboxUrl(url)}
                                  />
                                ))}
                              </div>
                            </div>
                          )}
                          {isExpanded && (!ep.gridImages || ep.gridImages.length === 0) && (
                            <div style={{ marginTop: 8, color: '#999', fontSize: 12 }}>
                              暂无九宫格图片
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </details>
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
                      <ChevronIcon open={chapterOpen} />
                      <BookIcon />
                      <h2 className={styles.chapterTitle}>第{chapter.chapterIndex}章 {chapter.title}</h2>
                      <span className={styles.chapterProgress}>
                        <span className={styles.chapterProgressText}>{chDoneCount}/{chTotalCount}</span>
                      </span>
                    </button>
                    <div className={styles.chapterBatchActions}>
                      <button
                        className={styles.btnGhost}
                        onClick={() => handleChapterBatchVideo(chapter.chapterIndex)}
                        disabled={chAllDone}
                      >
                        {chAllDone ? '已完成' : '批量生成'}
                      </button>
                      <button
                        className={styles.btnGhost}
                        onClick={() => handleChapterBatchEnhance(chapter.chapterIndex)}
                        disabled={!!activeBatchScope || batchEnhancingEpisodeId !== null || chAllDone}
                      >
                        {activeBatchScope === chScope('enhance') ? <><SpinIcon /> 润色中...</> : '润色'}
                      </button>
                      <button
                        className={styles.btnGhost}
                        onClick={() => handleChapterBatchTts(chapter.chapterIndex)}
                        disabled={!!activeBatchScope || isBatchTtsLoading}
                      >
                        {activeBatchScope === chScope('tts') ? <><SpinIcon /> 生成中...</> : '旁白'}
                      </button>
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
                              <button
                                className={styles.btnGhost}
                                onClick={() => handleBatchGenerateTts(ep.episodeId)}
                                disabled={isBatchTtsLoading}
                              >
                                {isBatchTtsLoading ? <><SpinIcon /> 生成中...</> : `批量旁白 (${epTtsCompletedCount}/${epTtsTotalCount})`}
                              </button>
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
                          </div>

                          {/* Panel video rows */}
                          <div className={styles.panelVideoList}>
                            {ep.segments.map((seg, idx) => (
                              <VideoSegmentRow
                                key={idx}
                                episode={ep}
                                segment={seg}
                                segmentIndex={idx}
                                isComicCommentary={isComicCommentary}
                                generatingVideoKeys={generatingVideoKeys}
                                expandedPanelKey={expandedPanelKey}
                                onGenerateVideo={handleGenerateVideo}
                                onGenerateTts={handleGenerateTts}
                                onMergeAudio={handleMergeAudio}
                                onTogglePanel={setExpandedPanelKey}
                                onOpenPromptModal={openPromptModal}
                                onOpenLightbox={setLightboxUrl}
                              />
                            ))}
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
