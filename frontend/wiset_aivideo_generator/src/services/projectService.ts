/**
 * 项目相关API服务
 */

import { get, post, patch } from './apiClient';
import type {
  CreateProjectRequest,
  CreateProjectResponse,
  Episode,
  GenerateEpisodesRequest,
  GenerateScriptResponse,
  Project,
  ProjectListItem,
  ProjectStatusInfo,
  ReviseScriptRequest,
  ReviseScriptResponse,
  ScriptContentResponse
} from './types/project.types';
import type { ApiResponse, PaginatedResponse } from './types/auth.types';

/**
 * 创建项目
 */
export async function createProject(data: CreateProjectRequest): Promise<ApiResponse<CreateProjectResponse>> {
  return post<ApiResponse<CreateProjectResponse>>('/api/projects', data);
}

/**
 * AI 重新生成大纲（修改剧本）
 * @param projectId 项目ID
 * @param data 修改意见和当前大纲
 * @returns 修订响应
 */
export async function reviseScript(
  projectId: string,
  data: ReviseScriptRequest
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/script/revise`, data);
}

/**
 * 触发剧本生成
 * @param projectId 项目ID
 * @returns 生成响应
 */
export async function generateScript(projectId: string): Promise<ApiResponse<GenerateScriptResponse>> {
  return post<ApiResponse<GenerateScriptResponse>>(`/api/projects/${projectId}/script/generate`);
}

/**
 * 获取剧本内容
 * @param projectId 项目ID
 * @returns 剧本内容
 */
export async function getScript(projectId: string): Promise<ApiResponse<ScriptContentResponse>> {
  return get<ApiResponse<ScriptContentResponse>>(`/api/projects/${projectId}/script`);
}

/**
 * 生成指定章节的剧集
 * @param projectId 项目ID
 * @param data 生成参数
 * @returns 生成响应
 */
export async function generateEpisodes(
  projectId: string,
  data: GenerateEpisodesRequest
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/script/episodes/generate`, data);
}

/**
 * 确认剧本，进入下一阶段
 * @param projectId 项目ID
 * @returns 确认响应
 */
export async function confirmScript(projectId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/script/confirm`);
}

/**
 * 直接保存用户编辑的大纲
 * @param projectId 项目ID
 * @param outline 大纲内容
 */
export async function updateScriptOutline(
  projectId: string,
  outline: string
): Promise<ApiResponse<void>> {
  return patch<ApiResponse<void>>(`/api/projects/${projectId}/script/outline`, { outline });
}

/**
 * 批量生成所有剩余章节的剧集
 * @param projectId 项目ID
 */
export async function generateAllEpisodes(
  projectId: string
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/script/episodes/generate-all`);
}

/**
 * 获取项目列表（分页）
 */
export interface GetProjectsParams {
  status?: string;
  sortBy?: string;
  sortOrder?: string;
  page?: number;
  size?: number;
}

export async function getProjects(params?: GetProjectsParams): Promise<ApiResponse<PaginatedResponse<ProjectListItem>>> {
  return get<ApiResponse<PaginatedResponse<ProjectListItem>>>('/api/projects', { params });
}

/**
 * 获取项目详情
 * @param projectId 项目ID
 * @returns 项目详情
 */
export async function getProject(projectId: string): Promise<ApiResponse<Project>> {
  return get<ApiResponse<Project>>(`/api/projects/${projectId}`);
}

/**
 * 获取项目状态详情（包含步骤映射和可用操作）
 * @param projectId 项目ID
 * @returns 项目状态详情
 */
export async function getProjectStatus(projectId: string): Promise<ApiResponse<ProjectStatusInfo>> {
  return get<ApiResponse<ProjectStatusInfo>>(`/api/projects/${projectId}/status`);
}

/**
 * 推进流水线
 * @param projectId 项目ID
 * @param event 事件名称
 * @returns 推进响应
 */
export async function advanceStatus(
  projectId: string,
  direction: 'forward' | 'backward',
  event?: string
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/status/advance`, { direction, event });
}

/**
 * 开始视频拼接
 */
export async function startVideoAssembly(projectId: string): Promise<ApiResponse<Map<string, string>>> {
  return post<ApiResponse<Map<string, string>>>(`/api/projects/${projectId}/video/assemble`);
}

// ================= 分镜流程 API =================

/**
 * 获取单集分镜数据
 */
export async function getStoryboard(episodeId: string): Promise<ApiResponse<Episode>> {
  return get<ApiResponse<Episode>>(`/api/story/storyboard/${episodeId}`);
}

/**
 * 启动分镜生成流程
 */
export async function startStoryboard(projectId: string): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/start-storyboard', { projectId });
}

/**
 * 确认当前集分镜
 */
export async function confirmStoryboard(episodeId: string): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/confirm-storyboard', { episodeId });
}

/**
 * 修改当前集分镜
 */
export async function reviseStoryboard(
  episodeId: string,
  feedback: string
): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/revise-storyboard', { episodeId, feedback });
}

/**
 * 重试失败的分镜生成
 */
export async function retryStoryboard(episodeId: string): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/retry-storyboard', { episodeId });
}

/**
 * 从分镜审核进入生产
 */
export async function startProduction(projectId: string): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/start-production', { projectId });
}
