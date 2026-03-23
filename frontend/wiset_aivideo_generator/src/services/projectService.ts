/**
 * 项目相关API服务
 */

import { get, post, patch } from './apiClient';
import type {
  CreateProjectRequest,
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
import type { ApiResponse } from './types/auth.types';

// Mock 模式开关（可通过环境变量控制）
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true' || false;

/**
 * Mock 数据：创建项目响应
 */
function mockCreateProject(data: CreateProjectRequest): Promise<ApiResponse<Project>> {
  // 模拟延迟
  return new Promise<ApiResponse<Project>>((resolve) => {
    setTimeout(() => {
      resolve({
        code: 0,
        message: '创建成功',
        data: {
          id: Math.floor(Math.random() * 10000) + 1,
          storyPrompt: data.storyPrompt,
          genre: data.genre || '',
          visualStyle: data.visualStyle || '3D',
          targetAudience: data.targetAudience,
          totalEpisodes: data.totalEpisodes,
          episodeDuration: data.episodeDuration,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      });
    }, 1000); // 模拟 1 秒延迟
  });
}

/**
 * 创建项目
 * @param data 项目信息
 * @returns 创建响应，包含项目信息
 */
export async function createProject(data: CreateProjectRequest): Promise<ApiResponse<Project>> {
  // Mock 模式下返回模拟数据
  if (USE_MOCK) {
    return mockCreateProject(data);
  }

  return post<ApiResponse<Project>>('/api/projects', data);
}

/**
 * 修订/接受脚本
 * @param projectId 项目ID
 * @param data 修订参数
 * @returns 修订响应
 */
export async function reviseScript(
  projectId: string,
  data: ReviseScriptRequest
): Promise<ApiResponse<ReviseScriptResponse>> {
  return post<ApiResponse<ReviseScriptResponse>>(`/api/projects/${projectId}/revise-script`, data);
}

/**
 * 触发剧本生成
 * @param projectId 项目ID
 * @returns 生成响应
 */
export async function generateScript(projectId: string): Promise<ApiResponse<GenerateScriptResponse>> {
  return post<ApiResponse<GenerateScriptResponse>>(`/api/projects/${projectId}/generate-script`);
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
  return post<ApiResponse<void>>(`/api/projects/${projectId}/generate-episodes`, data);
}

/**
 * 确认剧本，进入下一阶段
 * @param projectId 项目ID
 * @returns 确认响应
 */
export async function confirmScript(projectId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/confirm-script`);
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
  return patch<ApiResponse<void>>(`/api/projects/${projectId}/script-outline`, { outline });
}

/**
 * 批量生成所有剩余章节的剧集
 * @param projectId 项目ID
 */
export async function generateAllEpisodes(
  projectId: string
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/generate-all-episodes`);
}

/**
 * 获取项目列表
 * @returns 项目列表
 */
export async function getProjects(): Promise<ApiResponse<ProjectListItem[]>> {
  return get<ApiResponse<ProjectListItem[]>>('/api/projects');
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
export async function advancePipeline(
  projectId: string,
  event: string
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/advance`, { event });
}

// ================= 分镜流程 API =================

/**
 * 获取单集分镜数据
 */
export async function getStoryboard(episodeId: number): Promise<ApiResponse<Episode>> {
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
export async function confirmStoryboard(episodeId: number): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/confirm-storyboard', { episodeId });
}

/**
 * 修改当前集分镜
 */
export async function reviseStoryboard(
  episodeId: number,
  feedback: string
): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/revise-storyboard', { episodeId, feedback });
}

/**
 * 重试失败的分镜生成
 */
export async function retryStoryboard(episodeId: number): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/retry-storyboard', { episodeId });
}

/**
 * 从分镜审核进入生产
 */
export async function startProduction(projectId: string): Promise<ApiResponse<string>> {
  return post<ApiResponse<string>>('/api/story/start-production', { projectId });
}
