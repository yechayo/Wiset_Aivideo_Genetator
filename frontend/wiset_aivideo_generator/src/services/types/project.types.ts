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
  scenes?: Scene[];
  // 后续可扩展更多字段
}

/**
 * 创建项目请求参数
 */
export interface CreateProjectRequest {
  storyPrompt: string;
  genre?: string;
  visualStyle?: string;
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
  | 'SCRIPT_REVIEW'
  | 'SCRIPT_CONFIRMED'
  | 'EPISODE_GENERATING'
  | 'CHARACTER_EXTRACTING'
  | 'CHARACTER_REVIEW'
  | 'CHARACTER_CONFIRMED'
  | 'CHARACTER_EXTRACTING_FAILED'
  | 'IMAGE_GENERATING'
  | 'IMAGE_REVIEW'
  | 'IMAGE_GENERATING_FAILED'
  | 'ASSET_LOCKED'
  | 'STORYBOARD_GENERATING'
  | 'STORYBOARD_REVIEW'
  | 'STORYBOARD_GENERATING_FAILED'
  | 'PRODUCING'
  | 'COMPLETED';

/**
 * 项目信息
 */
export interface Project {
  id?: number;
  projectId?: string; // 项目唯一标识（UUID）
  userId?: string; // 用户ID
  storyPrompt: string; // 故事提示词/大纲
  genre: string; // 类型（热血玄幻、都市异能等）
  visualStyle?: string; // 画面风格（3D/REAL/ANIME）
  targetAudience: string; // 目标受众
  totalEpisodes: number; // 总集数
  episodeDuration: number; // 单集时长（秒）
  status?: ProjectStatus; // 项目状态
  scriptRevisionNote?: string; // 剧本修改意见
  scriptOutline?: string; // 剧本大纲
  selectedChapter?: string; // 当前选中的章节
  episodesPerChapter?: number; // 每章集数（默认4）
  script?: Script;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 修订脚本请求参数（支持动态属性）
 */
export type ReviseScriptRequest = Record<string, string>;

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
 * 剧集信息
 */
export interface Episode {
  id?: number;
  projectId?: string;
  title: string;
  content: string;
  characters: string;
  keyItems: string;
  continuityNote?: string;
  visualStyleNote?: string;
  chapterTitle?: string;
  episodeNum?: number;
  outlineNode?: string;
  storyboardJson?: string;
  status?: string;
  errorMsg?: string;
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
  chapter: string;
  episodeCount: number;
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
  storyboardCurrentEpisode?: number;
  storyboardTotalEpisodes?: number;
  storyboardReviewEpisodeId?: string;
  storyboardAllConfirmed?: boolean;
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
 * 角色生成状态（含图片URL）
 */
export interface CharacterStatus {
  charId: string;
  name: string;
  role: string;
  expressionStatus: string;    // pending/generating/completed/failed
  threeViewStatus: string;
  expressionError: string;
  threeViewError: string;
  isGeneratingExpression: boolean;
  isGeneratingThreeView: boolean;
  visualStyle: string;         // 3D/REAL/ANIME
  expressionGridUrl: string;
  threeViewGridUrl: string;
}
