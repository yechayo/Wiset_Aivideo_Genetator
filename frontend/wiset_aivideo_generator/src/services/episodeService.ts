/**
 * 剧集生产相关API服务
 */

import { get, post } from './apiClient';
import type { ApiResponse } from './types/auth.types';
import type {
  ProductionPipelineResponse,
  GridInfoResponse,
  SplitGridPageResponse,
  VideoSegmentInfo,
  PanelState,
} from './types/episode.types';

/** 获取项目下的剧集列表 */
export async function getEpisodes(projectId: string): Promise<ApiResponse<any[]>> {
  return get<ApiResponse<any[]>>(`/api/story/episodes?projectId=${projectId}`);
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

