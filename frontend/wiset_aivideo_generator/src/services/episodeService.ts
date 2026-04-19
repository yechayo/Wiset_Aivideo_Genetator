/**
 * 剧集生产相关API服务
 */

import { get, post, put } from './apiClient';
import type { ApiResponse, PaginatedResponse } from './types/auth.types';
import type {
  PanelGridStatusResponse,
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

// ================= Panel 生产状态 API =================

/** 批量获取所有 Panel 生产状态 */
export async function getBatchProductionStatuses(
  projectId: string,
  episodeId: number,
): Promise<ApiResponse<PanelGridStatusResponse[]>> {
  return get<ApiResponse<PanelGridStatusResponse[]>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/production-statuses`,
  );
}

// ================= 脚本生成 API =================

/** 生成分集剧本与分镜（单集生成） */
export async function generateEpisodeScripts(
  projectId: string,
  episodeId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/script`,
  );
}

// ================= 单分镜编辑 API =================

/** 更新单个分镜（可编辑字段 + locked） */
export async function updateShot(
  projectId: string,
  episodeId: number,
  shotIndex: number,
  updates: Record<string, any>,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/shots/${shotIndex}`,
    updates,
  );
}

// ================= 整集九宫格审核 API（新流程） =================

/** 获取整集九宫格状态 */
export async function getEpisodeGridStatus(
  projectId: string, episodeId: number,
): Promise<ApiResponse<any>> {
  return get<ApiResponse<any>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid`,
  );
}

/** 审核通过整集九宫格 */
export async function approveEpisodeGrid(
  projectId: string, episodeId: number,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid/approve`,
  );
}

/** 拒绝整集九宫格 */
export async function rejectEpisodeGrid(
  projectId: string, episodeId: number, reason: string,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid/reject`,
    { reason },
  );
}

/** 重新生成整集九宫格 */
export async function regenerateEpisodeGrid(
  projectId: string,
  episodeId: number,
  fullPrompt?: string,
  /** 多页提示词数组（每页一个 prompt） */
  gridPrompts?: string[],
): Promise<ApiResponse<number>> {
  const body: Record<string, any> = {};
  if (fullPrompt !== undefined) body.fullPrompt = fullPrompt;
  if (gridPrompts !== undefined) body.gridPrompts = gridPrompts;
  return post<ApiResponse<number>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid/regenerate`,
    Object.keys(body).length > 0 ? body : undefined,
  );
}

/** 逐页生成/重新生成九宫格 */
export async function regenerateEpisodeGridPage(
  projectId: string,
  episodeId: number,
  pageIndex: number,
  prompt?: string,
): Promise<ApiResponse<{ genVersion: string }>> {
  const body: Record<string, any> = {};
  if (prompt !== undefined) body.prompt = prompt;
  return post<ApiResponse<{ genVersion: string }>>(
    `/api/projects/${projectId}/episodes/${episodeId}/grid/regenerate/page/${pageIndex}`,
    Object.keys(body).length > 0 ? body : undefined,
  );
}

/**
 * 审核通过分镜文本
 */
export async function approvePanel(
  projectId: string, episodeId: number,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panel/approve`,
  );
}

/**
 * 退回分镜文本
 */
export async function rejectPanel(
  projectId: string, episodeId: number, reason: string,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panel/reject`,
    { reason },
  );
}

/**
 * 退回脚本阶段（从4b退回4a，清除九宫格数据）
 */
export async function rejectToScript(
  projectId: string, episodeId: number,
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panel/reject-to-script`,
  );
}

// ================= 九宫格审核 API =================

/** 审核通过九宫格 */
export async function approveGrid(projectId: string, episodeId: number, panelId: number): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/grid/approve`);
}

/** 拒绝九宫格 */
export async function rejectGrid(projectId: string, episodeId: number, panelId: number, reason: string): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/grid/reject`, { reason });
}

/** 重新生成九宫格 */
export async function regenerateGrid(projectId: string, episodeId: number, panelId: number, customHint?: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/grid/regenerate`, customHint ? { customHint } : {});
}

/** 获取单 Panel 生产状态 */
export async function getPanelGridStatus(
  projectId: string, episodeId: number, panelId: number,
): Promise<ApiResponse<PanelGridStatusResponse>> {
  return get<ApiResponse<PanelGridStatusResponse>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/production-status`,
  );
}

// ================= TTS 旁白生成 API =================

/** 生成单个 Panel 的 TTS 旁白 */
export async function generatePanelTts(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/tts`,
  );
}

/** 批量生成一集所有 Panel 的 TTS 旁白 */
export async function batchGenerateTts(
  projectId: string,
  episodeId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/tts/batch`,
  );
}

// ================= 音视频合并 API =================

/** 合并单个 Panel 的视频与 TTS 旁白 */
export async function mergePanelAudio(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/merge-audio`,
  );
}

/** 批量合并一集所有 Panel 的视频与 TTS 旁白 */
export async function batchMergeAudio(
  projectId: string,
  episodeId: number,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/merge-audio/batch`,
  );
}

/** 一键合成：拼接所有已合并面板为一集完整视频 */
export async function composeEpisode(
  projectId: string,
  episodeId: number,
): Promise<ApiResponse<{ composedVideoUrl: string; panelCount: number }>> {
  return post<ApiResponse<{ composedVideoUrl: string; panelCount: number }>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/compose-episode`,
  );
}

// ================= 视频生成 API =================

/** 获取视频生成提示词 */
/** 视频提示词接口响应（兼容单 prompt 和 Omni 多 shot） */
export interface VideoPromptResponse {
  prompt?: string;
  mode?: string;  // 'omni' for Kling multi-shot
  prompts?: Array<{ index: number; prompt: string; duration?: number }>;
}

export async function getVideoPrompt(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<VideoPromptResponse>> {
  return get<ApiResponse<VideoPromptResponse>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video/prompt`,
  );
}

/** 增强视频生成提示词（消耗1积分） */
export async function enhanceVideoPrompt(
  projectId: string,
  episodeId: number,
  panelId: number,
): Promise<ApiResponse<VideoPromptResponse>> {
  return post<ApiResponse<VideoPromptResponse>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video/prompt/enhance`,
  );
}

/** 生成视频 */
export async function generateVideo(
  projectId: string,
  episodeId: number,
  panelId: number,
  offPeak: boolean = false,
  customPrompt?: string,
  videoModel?: string,
  customOmniPrompts?: Array<{ prompt: string; duration: number }>,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video`,
    { offPeak, customPrompt, videoModel, customOmniPrompts },
  );
}

/** 参考图视频模式生成视频 */
export async function generateVideoRef(
  projectId: string,
  episodeId: number,
  panelId: number,
  offPeak: boolean = false,
  customPrompt?: string,
  videoModel?: string,
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(
    `/api/projects/${projectId}/episodes/${episodeId}/panels/${panelId}/video-ref`,
    { offPeak, customPrompt, videoModel },
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

/** 获取剧集角色参考图（兼容老项目） */
export async function getCharacterReferences(
  projectId: string, episodeId: number,
): Promise<ApiResponse<{ name: string; url: string; role: string }[]>> {
  return get<ApiResponse<{ name: string; url: string; role: string }[]>>(
    `/api/projects/${projectId}/episodes/${episodeId}/character-references`,
  );
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

// ================= 音色试听 API =================

/** 试听音色：生成一段示例文本的 TTS 音频，返回 audioUrl */
export async function previewVoice(voiceId: string): Promise<ApiResponse<{ audioUrl: string }>> {
  return post<ApiResponse<{ audioUrl: string }>>(
    '/api/voice/preview',
    { voiceId },
  );
}
