/**
 * 项目相关API服务
 */

import { post } from './apiClient';
import type {
  CreateProjectRequest,
  Project,
  ReviseScriptRequest,
  ReviseScriptResponse
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
          genre: data.genre,
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
