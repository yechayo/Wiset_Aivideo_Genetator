/**
 * 项目相关类型定义
 */

/**
 * 剧本场景
 */
export interface Scene {
  id?: number;
  description: string;
  duration?: number;
  // 后续可扩展更多字段
}

/**
 * 剧本信息
 */
export interface Script {
  title?: string;
  content?: string;
  outline?: string;
  scenes?: Scene[];
}

/**
 * 创建项目请求参数
 */
export interface CreateProjectRequest {
  storyPrompt: string;
  genre?: string;
  visualStyle?: VisualStyle;
  targetAudience: string;
  totalEpisodes: number;
  episodeDuration: number;
}

/**
 * 项目状态枚举
 */
export type ProjectStatus =
  | 'DRAFT'
  | 'OUTLINE_GENERATING'
  | 'OUTLINE_REVIEW'
  | 'OUTLINE_GENERATING_FAILED'
  | 'EPISODE_GENERATING'
  | 'EPISODE_GENERATING_FAILED'
  | 'SCRIPT_REVIEW'
  | 'SCRIPT_CONFIRMED'
  | 'CHARACTER_EXTRACTING'
  | 'CHARACTER_REVIEW'
  | 'CHARACTER_CONFIRMED'
  | 'CHARACTER_EXTRACTING_FAILED'
  | 'IMAGE_GENERATING'
  | 'IMAGE_REVIEW'
  | 'IMAGE_GENERATING_FAILED'
  | 'ASSET_LOCKED'
  | 'PANEL_GENERATING'
  | 'PANEL_REVIEW'
  | 'PANEL_GENERATING_FAILED'
  | 'PRODUCING'
  | 'VIDEO_ASSEMBLING'
  | 'COMPLETED';

/**
 * 创建项目响应
 */
export interface CreateProjectResponse {
  projectId: string;
}

/**
 * 项目信息
 */
export interface Project {
  id?: number;
  projectId?: string;
  userId?: string;
  status?: string;
  deleted?: boolean;
  projectInfo?: ProjectInfoData;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 修订脚本请求参数
 */
export interface ReviseScriptRequest {
  revisionNote: string;
  currentOutline?: string;
}

/**
 * 修订脚本响应
 */
export interface ReviseScriptResponse {
  [key: string]: any;
}

/**
 * 触发剧本生成响应
 */
export interface GenerateScriptResponse {
  // 根据实际返回数据结构扩展
  scriptId?: string;
  status?: string;
}

/**
 * 剧集详细信息（嵌套在 Episode.episodeInfo 中）
 */
export interface EpisodeInfo {
  title: string;
  content: string;
  characters: string;
  keyItems: string;
  episodeNum?: number;
  retryCount?: number;
  chapterTitle?: string;
  continuityNote?: string;
  visualStyleNote?: string;
}

/**
 * 剧集信息
 */
export interface Episode {
  id?: number;
  projectId?: string;
  status?: string;
  errorMsg?: string;
  episodeInfo?: EpisodeInfo;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 分镜面板
 */
export interface StoryboardPanel {
  scene: string;
  characters: string;
  shot_size: string;
  camera_angle: string;
  dialogue: string;
  effects: string;
}

/**
 * 分镜数据
 */
export interface StoryboardData {
  panels: StoryboardPanel[];
}

/** 视觉风格枚举 */
export type VisualStyle = '3D' | 'REAL' | 'ANIME' | 'MANGA' | 'INK' | 'CYBERPUNK';

/**
 * 项目基本信息（嵌套在 Project.projectInfo 中）
 */
export interface ProjectInfoData {
  genre?: string | null;
  script?: Script;
  storyPrompt?: string;
  visualStyle?: VisualStyle;
  totalEpisodes?: number;
  targetAudience?: string;
  episodeDuration?: number;
  selectedChapter?: string;
  episodesPerChapter?: number;
}

/**
 * 完整的脚本内容响应
 */
export interface ScriptContentResponse {
  outline: string;
  chapters: string[];
  nextChapter: string | null;
  project: Project;
  generatedChapters: string[];
  pendingChapters: string[];
  episodes: Episode[];
  isSingleEpisode?: boolean;
  needGenerateScript?: boolean;
}

/**
 * 生成剧集请求参数
 */
export interface GenerateEpisodesRequest {
  chapter: number;
  episodeCount?: number;
  modificationSuggestion?: string;
}

/**
 * 项目状态信息（从后端同步）
 */
export interface ProjectStatusInfo {
  projectId: string;
  statusCode: string;
  statusDescription: string;
  currentStep: number;
  isGenerating: boolean;
  isFailed: boolean;
  isReview: boolean;
  completedSteps: number[];
  availableActions: string[];
  productionProgress?: number;
  productionSubStage?: string;
  panelCurrentEpisode?: number;
  panelTotalEpisodes?: number;
  panelReviewEpisodeId?: string;
  panelAllConfirmed?: boolean;
}

/**
 * 项目列表项（后端返回，含状态映射）
 */
export interface ProjectListItem {
  projectId: string;
  storyPrompt: string;
  genre: string;
  visualStyle?: string;
  targetAudience: string;
  totalEpisodes: number;
  episodeDuration: number;
  statusCode: string;
  statusDescription: string;
  currentStep: number;
  isGenerating: boolean;
  isFailed: boolean;
  isReview: boolean;
  completedSteps: number[];
  createdAt?: string;
  updatedAt?: string;
}

// ================= 角色相关类型 =================



/**
 * 角色列表项（后端返回）
 */
export interface CharacterListItem {
  charId: string;
  name: string;
  role: string;
  personality: string;
  background?: string;
  voice?: string;
  appearance: string;
  visualStyle: string;
  expressionStatus: string | null;
  threeViewStatus: string | null;
  confirmed: boolean;
  createdAt: string;
}

/**
 * 角色草稿（从剧本提取的角色）
 */
export interface CharacterDraft {
  charId: string;
  name: string;
  role: string;          // 主角/反派/配角
  personality: string;
  appearance: string;
  background: string;
  confirmed: boolean;
}

/**
 * 角色生成状态（详情接口返回）
 */
export interface CharacterStatus {
  charId: string;
  name: string;
  role: string;
  expressionStatus: string;    // pending/generating/completed/failed
  threeViewStatus: string;
  expressionError?: string;
  threeViewError?: string;
  isGeneratingExpression?: boolean;
  isGeneratingThreeView?: boolean;
  visualStyle?: string;         // 3D/ANIME/COMIC
  expressionGridUrl?: string;
  threeViewGridUrl?: string;
}
