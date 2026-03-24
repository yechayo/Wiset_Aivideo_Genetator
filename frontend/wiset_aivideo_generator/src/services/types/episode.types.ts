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


// ===== 单分镜视频生产相关类型 =====

/** 单分镜生产阶段状态 */
export type PanelStageStatus = 'pending' | 'generating' | 'completed' | 'failed';

/** 生产流水线阶段 */
export type ProductionStage = 'background' | 'fusion' | 'transition' | 'video';

/** 分镜整体状态 */
export type PanelOverallStatus = 'pending' | 'in_progress' | 'completed' | 'failed';

/** 背景图状态响应 */
export interface PanelBackgroundResponse {
  panelIndex: number;
  backgroundUrl: string | null;
  status: PanelStageStatus;
  prompt: string | null;
}

/** 融合图状态响应 */
export interface PanelFusionResponse {
  panelIndex: number;
  fusionUrl: string | null;
  status: PanelStageStatus;
  referenceBackground: string | null;
  characterRefs: string[];
}

/** 过渡融合图状态响应 */
export interface PanelTransitionResponse {
  panelIndex: number;
  transitionUrl: string | null;
  status: PanelStageStatus;
  sourceFusionUrl: string | null;
  sourceTailFrameUrl: string | null;
}

/** 视频任务状态响应 */
export interface PanelVideoTaskResponse {
  panelIndex: number;
  videoUrl: string | null;
  status: PanelStageStatus;
  taskId: string | null;
  duration: number | null;
  errorMessage: string | null;
}

/** 尾帧响应 */
export interface PanelTailFrameResponse {
  panelIndex: number;
  tailFrameUrl: string | null;
  sourceVideoUrl: string | null;
  status: string;
}

/** 单分镜完整生产状态 */
export interface PanelProductionStatusResponse {
  panelIndex: number;
  overallStatus: PanelOverallStatus;
  backgroundUrl: string | null;
  backgroundStatus: PanelStageStatus;
  fusionUrl: string | null;
  fusionStatus: PanelStageStatus;
  transitionUrl: string | null;
  transitionStatus: PanelStageStatus;
  videoUrl: string | null;
  videoStatus: PanelStageStatus;
  videoDuration: number | null;
  tailFrameUrl: string | null;
  currentStage: ProductionStage;
}

/** 合成结果响应 */
export interface CompositionResultResponse {
  finalVideoUrl: string;
  duration: number;
  totalSegments: number;
  status: string;
}

/** 一键生产请求 */
export interface AutoProduceRequest {
  startFrom?: number;
}

/** 融合图生成请求 */
export interface FusionRequest {
  backgroundUrl: string;
  characterRefs: string[];
}

/** 过渡融合图生成请求 */
export interface TransitionRequest {
  fusionUrl: string;
}

/** 单分镜生产请求 */
export interface ProduceRequest {
  backgroundUrl?: string;
  characterRefs?: string[];
}
