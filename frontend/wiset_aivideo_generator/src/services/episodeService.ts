/**
 * 剧集生产相关API服务
 */

import { get, post } from './apiClient';
import type { ApiResponse } from './types/auth.types';
import type { ProductionStatusResponse, ProductionPipelineResponse, GridInfoResponse, VideoSegmentInfo } from './types/episode.types';

/** 获取项目下的剧集列表 */
export async function getEpisodes(projectId: string): Promise<ApiResponse<any[]>> {
  return get<ApiResponse<any[]>>(`/api/story/episodes?projectId=${projectId}`);
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

/** 提交单页融合结果（P1-1 多页融合） */
export async function submitFusionPage(
  episodeId: string,
  pageIndex: number,
  fusedReferenceImageUrl: string,
): Promise<ApiResponse<{ totalFused: number; pageIndex: number }>> {
  return post<ApiResponse<{ totalFused: number; pageIndex: number }>>(
    `/api/episodes/${episodeId}/submit-fusion-page`,
    { pageIndex, fusedReferenceImageUrl },
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
