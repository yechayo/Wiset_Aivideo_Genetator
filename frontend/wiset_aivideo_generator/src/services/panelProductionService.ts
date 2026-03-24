/**
 * 单分镜视频生产API服务
 */

import { get, post } from './apiClient';
import type { ApiResponse } from './types/auth.types';
import type {
  PanelBackgroundResponse,
  PanelFusionResponse,
  PanelTransitionResponse,
  PanelVideoTaskResponse,
  PanelTailFrameResponse,
  PanelProductionStatusResponse,
  CompositionResultResponse,
  FusionRequest,
  TransitionRequest,
  ProduceRequest,
} from './types/episode.types';

/** 获取分镜背景图状态 */
export async function getBackgroundStatus(episodeId: string, panelIndex: number) {
  return get<ApiResponse<PanelBackgroundResponse>>(`/api/episodes/${episodeId}/panels/${panelIndex}/background`);
}

/** 生成背景图 */
export async function generateBackground(episodeId: string, panelIndex: number) {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/panels/${panelIndex}/background`);
}

/** 获取融合图状态 */
export async function getFusionStatus(episodeId: string, panelIndex: number) {
  return get<ApiResponse<PanelFusionResponse>>(`/api/episodes/${episodeId}/panels/${panelIndex}/fusion`);
}

/** 生成融合图 */
export async function generateFusion(episodeId: string, panelIndex: number, request: FusionRequest) {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/panels/${panelIndex}/fusion`, request);
}

/** 获取过渡融合图状态 */
export async function getTransitionStatus(episodeId: string, panelIndex: number) {
  return get<ApiResponse<PanelTransitionResponse>>(`/api/episodes/${episodeId}/panels/${panelIndex}/transition`);
}

/** 生成过渡融合图 */
export async function generateTransition(episodeId: string, panelIndex: number, request: TransitionRequest) {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/panels/${panelIndex}/transition`, request);
}

/** 获取尾帧 */
export async function getTailFrame(episodeId: string, panelIndex: number) {
  return get<ApiResponse<PanelTailFrameResponse>>(`/api/episodes/${episodeId}/panels/${panelIndex}/tail-frame`);
}

/** 获取视频任务状态 */
export async function getVideoTaskStatus(episodeId: string, panelIndex: number) {
  return get<ApiResponse<PanelVideoTaskResponse>>(`/api/episodes/${episodeId}/panels/${panelIndex}/video-task`);
}

/** 获取单分镜完整生产状态 */
export async function getPanelProductionStatus(episodeId: string, panelIndex: number) {
  return get<ApiResponse<PanelProductionStatusResponse>>(`/api/episodes/${episodeId}/panels/${panelIndex}/production-status`);
}

/** 单分镜一键生产 */
export async function produceSinglePanel(episodeId: string, panelIndex: number, request?: ProduceRequest) {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/panels/${panelIndex}/produce`, request || {});
}

/** 一键生成所有分镜视频 */
export async function autoProduceAll(episodeId: string, startFrom?: number) {
  return post<ApiResponse<void>>(`/api/episodes/${episodeId}/auto-produce-all`, { startFrom: startFrom ?? 0 });
}

/** 合成最终视频 */
export async function synthesizeEpisode(episodeId: string) {
  return post<ApiResponse<CompositionResultResponse>>(`/api/episodes/${episodeId}/synthesize`);
}