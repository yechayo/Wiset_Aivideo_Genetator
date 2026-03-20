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
  standardImageUrl: string | null;
}

/** 单页网格信息（P1-1 多页网格） */
export interface GridPageInfo {
  sceneGridUrl: string;
  sceneGroupIndex: number;
  location: string | null;
  characters: string[] | null;
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
