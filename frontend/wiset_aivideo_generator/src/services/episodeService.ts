/**
 * 剧集生产相关API服务
 */

import { get, post } from './apiClient';
import type { ApiResponse, PaginatedResponse } from './types/auth.types';
import type {
  PanelProductionStatusResponse,
  ProductionPipelineResponse,
  VideoSegmentInfo,
  PanelState,
  SplitGridPageResponse,
  GridInfoResponse,
} from './types/episode.types';

// ================= 剧集与分镜 CRUD =================

/** 获取项目下的剧集列表（分页） */
export async function getEpisodes(
  projectId: string,
  params?: { page?: number; size?: number; name?: string }
): Promise<ApiResponse<PaginatedResponse>> {
  return get<ApiResponse<PaginatedResponse>>(`/api/projects/${projectId}/episodes`, {
    params: { page: 1, size: 10, ...params },
  });
}

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

// ================= Panel 生产状态 API =================

/** 获取单 Panel 完整生产状态 */
export async function getPanelProductionStatus(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<PanelProductionStatusResponse>> {
  return get<ApiResponse<PanelProductionStatusResponse>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/production-status`,
  );
}

/** 批量获取所有 Panel 生产状态 */
export async function getBatchProductionStatuses(
  projectId: string,
  episodeId: number,
): Promise<ApiResponse<PanelProductionStatusResponse[]>> {
  return get<ApiResponse<PanelProductionStatusResponse[]>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/production-statuses`,
  );
}

// ================= 背景图 API =================

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

/** 重新生成背景图 */
export async function regenerateBackground(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/background/regenerate`,
  );
}

// ================= 四宫格漫画 API =================

/** 获取四宫格漫画状态 */
export async function getComicStatus(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<any>> {
  return get<ApiResponse<any>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/comic`,
  );
}

/** 生成四宫格漫画 */
export async function generateComic(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/comic`,
  );
}

/** 审核通过四宫格漫画 */
export async function approveComic(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/comic/approve`,
  );
}

/** 退回重生成四宫格漫画 */
export async function reviseComic(
  projectId: string,
  episodeId: number,
  panelId: number,
  feedback: string,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/comic/revise`,
    { feedback },
  );
}

// ================= 视频生成 API =================

/** 生成视频 */
export async function generateVideo(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video`,
  );
}

/** 重试失败的视频生成 */
export async function retryVideo(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video/retry`,
  );
}

/** AI 修改单个分镜 */
export async function reviseSinglePanel(
  projectId: string,
  episodeId: number,
  panelId: number,
  feedback: string,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/revise-single`,
    { feedback },
  );
}

/** 更新分镜信息（手动编辑） */
export async function updatePanel(
  projectId: string,
  episodeId: number,
  panelId: number,
  panelInfo: Record<string, any>,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}`,
    { panelInfo },
  );
}

// ================= 旧版 API（Step6 等页面使用，后端端点已废弃） =================

/** @deprecated 旧版单集生产状态，后端端点已删除 */
export async function getProductionStatus(episodeId: string): Promise<ApiResponse<any>> {
  return get<ApiResponse<any>>(`/api/episodes/${episodeId}/production-status`);
}

/** @deprecated 旧版管线状态，后端端点已删除 */
export async function getProductionPipeline(projectId: string): Promise<ApiResponse<ProductionPipelineResponse>> {
  return get<ApiResponse<ProductionPipelineResponse>>(`/api/episodes/project/${projectId}/pipeline`);
}

/** @deprecated 旧版网格信息，后端端点已删除 */
export async function getGridInfo(episodeId: string): Promise<ApiResponse<GridInfoResponse>> {
  return get<ApiResponse<GridInfoResponse>>(`/api/episodes/${episodeId}/grid-info`);
}

/** @deprecated 旧版上传融合图，后端端点已删除 */
export async function uploadFusionImage(episodeId: string, file: File): Promise<ApiResponse<string>> {
  const formData = new FormData();
  formData.append('file', file);
  return post<ApiResponse<string>>(`/api/episodes/${episodeId}/fusion-image`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

/** @deprecated 旧版提交融合，后端端点已删除 */
export async function submitFusion(episodeId: string, fusedReferenceImageUrl: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/submit-fusion`, { fusedReferenceImageUrl });
}

/** @deprecated 旧版融合页，后端端点已删除 */
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

/** @deprecated 旧版切分网格，后端端点已删除 */
export async function splitGridPage(
  episodeId: string,
  pageIndex: number,
): Promise<ApiResponse<SplitGridPageResponse>> {
  return post<ApiResponse<SplitGridPageResponse>>(
    `/api/episodes/${episodeId}/split-grid-page`,
    { pageIndex },
  );
}

/** @deprecated 旧版视频片段列表，后端端点已删除 */
export async function getVideoSegments(episodeId: string): Promise<ApiResponse<VideoSegmentInfo[]>> {
  return get<ApiResponse<VideoSegmentInfo[]>>(`/api/episodes/${episodeId}/video-segments`);
}

/** @deprecated 旧版重试生产，后端端点已删除 */
export async function retryProduction(episodeId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/retry-production`);
}

/** @deprecated 旧版面板状态，后端端点已删除 */
export async function getPanelStates(episodeId: string): Promise<ApiResponse<PanelState[]>> {
  return get<ApiResponse<PanelState[]>>(`/api/episodes/${episodeId}/panel-states`);
}

/** @deprecated 旧版单格视频生成，后端端点已删除 */
export async function generateSinglePanelVideo(
  episodeId: string,
  panelIndex: number,
): Promise<ApiResponse<{ panelIndex: number; status: string; groupId: string }>> {
  return post<ApiResponse<{ panelIndex: number; status: string; groupId: string }>>(
    `/api/episodes/${episodeId}/panels/${panelIndex}/generate-video`,
  );
}

/** @deprecated 旧版自动继续，后端端点已删除 */
export async function autoContinue(episodeId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/auto-continue`);
}

/** @deprecated 旧版融合页（带自动继续），后端端点已删除 */
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

/** @deprecated 旧版场景图重生成，后端端点已删除 */
export async function regenerateSceneImage(
  episodeId: string,
  panelIndex: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/episodes/${episodeId}/panels/${panelIndex}/regenerate-scene`,
  );
}
