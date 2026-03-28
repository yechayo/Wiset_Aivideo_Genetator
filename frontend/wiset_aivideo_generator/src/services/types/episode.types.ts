/**
 * 剧集生产相关类型定义
 */

/** 生产状态枚举 */
export type ProductionStatus =
  | 'NOT_STARTED'
  | 'ANALYZING'
  | 'GRID_GENERATING'
  | 'GRID_FUSION_PENDING'
  | 'BUILDING_PROMPTS'
  | 'GENERATING'
  | 'GENERATING_SUBS'
  | 'COMPOSING'
  | 'COMPLETED'
  | 'FAILED';

/** 单集生产状态响应（GET /api/episodes/{id}/production-status） */
export interface ProductionStatusResponse {
  productionId: string;
  episodeId: string;
  status: ProductionStatus;
  currentStage: string;
  progressPercent: number;
  progressMessage: string;
  totalPanels: number;
  completedPanels: number;
  sceneGridUrl: string | null;
  fusedReferenceImageUrl: string | null;
  finalVideoUrl: string | null;
  errorMessage: string | null;
}

/** 角色参考图信息 */
export interface CharacterReferenceInfo {
  characterName: string;
  threeViewGridUrl: string | null;
  expressionGridUrl: string | null;
}

/** 单页网格信息（P1-1 多页网格） */
export interface GridPageInfo {
  sceneGridUrl: string;
  sceneGroupIndex: number;
  location: string | null;
  characters: string[] | null;
  gridRows: number | null;
  gridColumns: number | null;
  fused: boolean;
  fusedPanels: string[] | null;
}

/** 网格拆分/融合所需信息（GET /api/episodes/{id}/grid-info） */
export interface GridInfoResponse {
  sceneGridUrl: string;
  characterReferences: CharacterReferenceInfo[];
  gridPages: GridPageInfo[];
  totalPages: number;
  gridColumns: number;
  gridRows: number;
  panelWidth: number;
  panelHeight: number;
  separatorPixels: number;
}

/** 后端切分后的单格结果（POST /api/episodes/{id}/split-grid-page） */
export interface SplitGridCell {
  pageIndex: number;
  cellIndex: number;
  panelIndex: number;
  panelId: string | null;
  imageUrl: string;
  placeholder: boolean;
  panelData: Record<string, unknown> | null;
}

/** 后端切分后的单页结果（POST /api/episodes/{id}/split-grid-page） */
export interface SplitGridPageResponse {
  pageIndex: number;
  rows: number;
  cols: number;
  skipped: boolean;
  errorMessage: string | null;
  cells: SplitGridCell[];
}

/** 视频片段信息（P1-7） */
export interface VideoSegmentInfo {
  panelIndex: number;
  videoUrl: string;
  targetDuration: number;
  videoPrompt: string;
  sceneDescription: string;
}

/** 管线阶段显示状态 */
export type StageDisplayStatus = 'pending' | 'active' | 'waiting_user' | 'completed' | 'failed';

/** 管线阶段DTO */
export interface PipelineStage {
  key: string;           // storyboard, analyzing, grid_generating, ...
  name: string;          // 分镜生成, 场景分析, ...
  displayStatus: StageDisplayStatus;
  progress: number;      // 0-100
  message: string;       // 阶段描述/错误信息
}

/** 面板状态响应（原子化模式） */
export interface PanelState {
  panelIndex: number;
  fusionStatus: 'pending' | 'completed';
  fusionUrl: string | null;
  promptText: string | null;
  videoStatus: 'pending' | 'generating' | 'completed' | 'failed';
  videoUrl: string | null;
  videoTaskId: string | null;
  sceneDescription: string | null;
  shotType: string | null;
  dialogue: string | null;
  panelId: string | null;
}

/** 生产管线全链路状态响应 */
export interface ProductionPipelineResponse {
  episodeId: string | null;
  episodeTitle: string | null;
  episodeStatus: string;      // DRAFT, GENERATING, DONE, FAILED
  productionStatus: string;   // NOT_STARTED, IN_PROGRESS, COMPLETED, FAILED
  stages: PipelineStage[];
  errorMessage: string | null;
  finalVideoUrl: string | null;
  sceneGridUrls: string[];
}

/** Step5 工作流阶段 */
export type WorkflowPhase = 'review' | 'scene-generating' | 'fusion' | 'video' | 'completed';

/** 场景图状态 */
export interface SceneImageState {
  url: string | null;
  generating: boolean;
  failed: boolean;
  prompt: string | null;  // 来自分镜 JSON 的 background.scene_desc
}

// ================= Panel 生产状态 DTO（对应后端 DTO） =================

/** 单 Panel 完整生产状态（对应后端 PanelProductionStatusResponse） */
export interface PanelProductionStatusResponse {
  panelId: number;
  overallStatus: string;
  currentStage: string;
  backgroundStatus: string;
  backgroundUrl: string | null;
  comicStatus: string;
  comicUrl: string | null;
  videoStatus: string;
  videoUrl: string | null;
  videoDuration: number | null;
  errorMessage: string | null;
}

/** 四宫格漫画状态（对应后端 ComicStatusResponse） */
export interface ComicStatusResponse {
  panelId: number;
  status: string;
  comicUrl: string | null;
  backgroundUrl: string | null;
  errorMessage: string | null;
}

/** 视频状态（对应后端 VideoStatusResponse） */
export interface VideoStatusResponse {
  panelId: number;
  status: string;
  videoUrl: string | null;
  taskId: string | null;
  errorMessage: string | null;
  duration: number | null;
}

/** 背景图状态（对应后端 PanelBackgroundResponse） */
export interface PanelBackgroundResponse {
  panelId: number;
  panelIndex: number;
  backgroundUrl: string | null;
  status: string;
  prompt: string | null;
}
