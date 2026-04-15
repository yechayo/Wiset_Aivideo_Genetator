/** 片段流水线状态 */
export type SegmentPipelineStep = 'pending' | 'grid_generating' | 'grid_review' | 'grid_approved' | 'video_generating' | 'video_completed' | 'video_failed';

/** 分镜详细信息 */
export interface PanelData {
  panelId: string;
  /** panelInfo 中的 panel_id（如 "p1"、"p2"），用于匹配 panelPlan */
  planPanelId: string;
  composition: string;
  shotType: string;
  cameraAngle: string;
  cameraMovement: string;
  pacing: string;
  dialogue: string;
  scene: string;
  characters: any[];
  background: any;
  imagePromptHint: string;
  sfx: string[];
  duration?: number;
  // === 新流程字段 ===
  totalShots?: number;
  totalDuration?: number;
  visualStyle?: string;
  fusionImageUrl?: string | null;
}

/** 片段状态 */
export interface SegmentState {
  segmentIndex: number;
  title: string;
  synopsis: string;
  sceneThumbnail: string | null;
  characterAvatars: { charId: string; name: string; avatarUrl: string }[];
  pipelineStep: SegmentPipelineStep;
  gridImages: string[];
  gridStatus: string;
  fusionImageUrl: string | null;
  shots: any[];
  videoUrl: string | null;
  feedback: string;
  panelData?: PanelData;
  videoTaskId?: string | null;
  videoModel?: string | null;
  videoOffPeak?: boolean | null;
  videoProgress?: number | null;     // 0-100
  videoCredits?: number | null;      // 积分消耗
  videoRefMode?: boolean | null;
  referenceImages?: string[];
  referenceImageLabels?: string[];
  // === TTS 旁白语音 ===
  ttsAudioUrl?: string | null;
  ttsStatus?: 'pending' | 'generating' | 'completed' | 'failed';
  ttsCredits?: number | null;
  // === 音视频合并 ===
  videoWithNarrationUrl?: string | null;
  mergeStatus?: 'pending' | 'generating' | 'completed' | 'failed';
}

/** Episode 级九宫格状态 */
export type EpisodeGridStatus = 'pending' | 'text_ready' | 'generating' | 'generated' | 'approved' | 'rejected' | 'failed';

/** Episode pipeline stage (frontend-derived, not stored in backend) */
export type PipelineStage = 'script' | 'grid' | 'video';

/** Derive current pipeline stage from panelApproved + gridStatus */
export function getPipelineStage(ep: {
  panelApproved?: boolean;
  gridStatus?: EpisodeGridStatus;
}): PipelineStage {
  if (!ep.panelApproved) return 'script';
  if (ep.gridStatus === 'approved') return 'video';
  return 'grid';
}

/** 切割后的分镜（带完整元数据） */
export interface SplitShot {
  shotNumber: number;
  splitImageUrl: string;
  duration: number;
  scene: string;
  characters: string[];
  shotSize: string;
  cameraAngle: string;
  cameraMovement: string;
  visualDescription: string;
  sceneDescription?: string;
  dialogue: string;
  visualEffects: string;
  audioEffects: string;
}

/** 单页宫格布局配置 */
export interface GridConfig {
  page: number;
  gridCols: number;
  gridRows: number;
  shotCount: number;
}

/** 剧集状态 */
export interface EpisodeState {
  episodeId: number;
  episodeIndex: number;
  title: string;
  /** panelPlan JSON 解析后的 scene_summary 映射：panel_id → scene_summary */
  sceneSummaryMap: Record<string, string>;
  segments: SegmentState[];
  /**
   * Shot-level segments for 4A script editing and 4B grid display.
   * Built from episodeInfo.shots (one per shot, e.g. 11 shots per episode).
   * Kept separate from panel-level 'segments' used by 4C for batch video generation.
   */
  shotSegments?: SegmentState[];
  // === 新流程：Episode 级九宫格 ===
  /** 整集九宫格状态 */
  gridStatus?: EpisodeGridStatus;
  /** 整集九宫格图 URL 列表（分页） */
  gridImages?: string[];
  /** 切割后的分镜列表（带完整元数据） */
  splitShots?: SplitShot[];
  /** 九宫格拒绝原因 */
  gridRejectionFeedback?: string | null;
  /** 4B 图片生成附加提示词（用户可编辑） */
  gridPromptHint?: string;
  /** 九宫格完整提示词（用户直接编辑后保存）- 兼容单页场景 */
  gridPrompt?: string;
  /** 多页九宫格提示词数组（每页一个 prompt，按页索引对应） */
  gridPrompts?: string[];
  /** 每页宫格布局配置（自适应：2×2 / 3×3） */
  gridConfigs?: GridConfig[];
  /** 角色参考图信息（后端生成九宫格时保存） */
  characterReferences?: { name: string; url: string; role: string }[];
  /** 分镜脚本审核通过标记（4a 审核通过后由后端设置） */
  panelApproved?: boolean;
  /** 是否使用新流程（episodeInfo 中有 gridStatus 字段） */
  isNewFlow?: boolean;
  /** 分集剧本生成状态（SSE 实时更新） */
  scriptStatus?: 'pending' | 'generating' | 'done';
  /** 分镜脚本生成状态（SSE 实时更新） */
  storyboardStatus?: 'pending' | 'generating' | 'done';
  /** Raw episodeInfo from backend Episode entity */
  episodeInfo?: Record<string, any>;
  // === 逐集合成视频 ===
  /** 逐集合成视频 URL */
  composedVideoUrl?: string | null;
  /** 逐集合成状态 */
  composedVideoStatus?: string;
}

/** 章节状态 */
export interface ChapterState {
  chapterIndex: number;
  title: string;
  episodes: EpisodeState[];
}

/** 片段子卡片展开状态（手风琴模式：同时只展开一个剧集和一个片段）*/
export interface ExpansionState {
  expandedEpisodeId: number | null;
  expandedSegmentKey: string | null; // "episodeId-segmentIndex"
}
