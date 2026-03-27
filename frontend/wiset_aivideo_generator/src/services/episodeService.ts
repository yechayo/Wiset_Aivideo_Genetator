/**
 * 剧集生产相关API服务
 */

import { get, post } from './apiClient';
import type { ApiResponse, PaginatedResponse } from './types/auth.types';
import type {
  ProductionStatusResponse,
  ProductionPipelineResponse,
  GridInfoResponse,
  SplitGridPageResponse,
  VideoSegmentInfo,
  PanelState,
} from './types/episode.types';

/** 获取剧集的分镜列表 */
export async function getPanels(projectId: string, episodeId: number): Promise<ApiResponse<any[]>> {
  return get<ApiResponse<any[]>>(`/api/projects/${projectId}/episodes/${episodeId}/panels`);
}

/** AI 生成分镜 */
export async function generatePanels(projectId: string, episodeId: number): Promise<ApiResponse<any>> {
  return post<ApiResponse<any>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/generate`);
}

/** 分镜生成任务状态 */
export async function getPanelGenerateStatus(
  projectId: string,
  episodeId: number,
  jobId: string
): Promise<ApiResponse<any>> {
  return get<ApiResponse<any>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/generate/${jobId}/status`);
}

/** 获取项目下的剧集列表（分页） */
export async function getEpisodes(
  projectId: string,
  params?: { page?: number; size?: number; name?: string }
): Promise<ApiResponse<PaginatedResponse>> {
  return get<ApiResponse<PaginatedResponse>>(`/api/projects/${projectId}/episodes`, {
    params: { page: 1, size: 10, ...params },
  });
}

/** 获取单集生产状态 */
export async function getProductionStatus(episodeId: string): Promise<ApiResponse<ProductionStatusResponse>> {
  return get<ApiResponse<ProductionStatusResponse>>(`/api/episodes/${episodeId}/production-status`);
}

/** 获取项目生产管线全链路状态（Step5页面可视化） */
export async function getProductionPipeline(projectId: string): Promise<ApiResponse<ProductionPipelineResponse>> {
  return get<ApiResponse<ProductionPipelineResponse>>(`/api/episodes/project/${projectId}/pipeline`);
}

/** 获取网格拆分/融合所需信息 */
export async function getGridInfo(episodeId: string): Promise<ApiResponse<GridInfoResponse>> {
  return get<ApiResponse<GridInfoResponse>>(`/api/episodes/${episodeId}/grid-info`);
}

/** 上传融合图（multipart） */
export async function uploadFusionImage(episodeId: string, file: File): Promise<ApiResponse<string>> {
  const formData = new FormData();
  formData.append('file', file);
  return post<ApiResponse<string>>(`/api/episodes/${episodeId}/fusion-image`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

/** 提交融合结果，恢复管线（旧接口兼容） */
export async function submitFusion(episodeId: string, fusedReferenceImageUrl: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/submit-fusion`, { fusedReferenceImageUrl });
}

/** 提交单页融合结果（逐格融合：每页9个URL） */
export async function submitFusionPage(
  episodeId: string,
  pageIndex: number,
  panelFusedUrls: string[],
): Promise<ApiResponse<{ totalFused: number; pageIndex: number }>> {
  return post<ApiResponse<{ totalFused: number; pageIndex: number }>>(
    `/api/episodes/${episodeId}/submit-fusion-page`,
    { pageIndex, panelFusedUrls },
  );
}

/** 后端切分单页网格（优先用于前后端联调，失败时前端可回退本地切分） */
export async function splitGridPage(
  episodeId: string,
  pageIndex: number,
): Promise<ApiResponse<SplitGridPageResponse>> {
  return post<ApiResponse<SplitGridPageResponse>>(
    `/api/episodes/${episodeId}/split-grid-page`,
    { pageIndex },
  );
}

/** 获取视频片段列表（P1-7） */
export async function getVideoSegments(episodeId: string): Promise<ApiResponse<VideoSegmentInfo[]>> {
  return get<ApiResponse<VideoSegmentInfo[]>>(`/api/episodes/${episodeId}/video-segments`);
}

/** 重试失败的生产 */
export async function retryProduction(episodeId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/retry-production`);
}

/** 获取所有面板状态（原子化模式） */
export async function getPanelStates(episodeId: string): Promise<ApiResponse<PanelState[]>> {
  return get<ApiResponse<PanelState[]>>(`/api/episodes/${episodeId}/panel-states`);
}

/** 单格视频生成（原子化模式） */
export async function generateSinglePanelVideo(
  episodeId: string,
  panelIndex: number,
): Promise<ApiResponse<{ panelIndex: number; status: string; groupId: string }>> {
  return post<ApiResponse<{ panelIndex: number; status: string; groupId: string }>>(
    `/api/episodes/${episodeId}/panels/${panelIndex}/generate-video`,
  );
}

/** 手动触发流水线继续（一键自动化） */
export async function autoContinue(episodeId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/auto-continue`);
}

/** 提交单页融合结果（支持 autoContinue 参数） */
export async function submitFusionPageWithAuto(
  episodeId: string,
  pageIndex: number,
  panelFusedUrls: string[],
  autoContinue: boolean,
): Promise<ApiResponse<{ totalFused: number; pageIndex: number }>> {
  return post<ApiResponse<{ totalFused: number; pageIndex: number }>>(
    `/api/episodes/${episodeId}/submit-fusion-page`,
    { pageIndex, panelFusedUrls, autoContinue },
  );
}

/** 单格场景图重生成 */
export async function regenerateSceneImage(
  episodeId: string,
  panelIndex: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/episodes/${episodeId}/panels/${panelIndex}/regenerate-scene`,
  );
}

/** 生成背景图（自动匹配角色） */
export async function generateBackground(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<any>> {
  return post<ApiResponse<any>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/background`,
  );
}

/** 获取背景图状态 */
export async function getBackgroundStatus(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<{
  panelId: number;
  panelIndex: number;
  backgroundUrl: string;
  status: string;
  prompt: string;
}>> {
  return get<ApiResponse<{
    panelId: number;
    panelIndex: number;
    backgroundUrl: string;
    status: string;
    prompt: string;
  }>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/background`,
  );
}

/** 修改分镜 */
export async function revisePanel(
  projectId: string,
  episodeId: number,
  panelId: number,
  feedback: string,
): Promise<ApiResponse<any>> {
  return post<ApiResponse<any>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/revise`,
    { feedback },
  );
}
