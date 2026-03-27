/**
 * 角色相关 API 服务
 */

import { get, post, put, del } from './apiClient';
import type { ApiResponse, PaginatedResponse } from './types/auth.types';
import type { CharacterDraft, CharacterListItem, CharacterStatus } from './types/project.types';

export interface GetCharactersParams {
  page?: number;
  size?: number;
  role?: string;
  name?: string;
}

/**
 * 获取项目角色列表（分页）
 */
export async function getCharacters(projectId: string, params?: GetCharactersParams): Promise<ApiResponse<PaginatedResponse<CharacterListItem>>> {
  return get<ApiResponse<PaginatedResponse<CharacterListItem>>>(`/api/projects/${projectId}/characters`, {
    params: { page: 1, size: 10, ...params },
  });
}

/**
 * 从剧本中提取角色
 */
export async function extractCharacters(projectId: string): Promise<ApiResponse<CharacterDraft[]>> {
  return post<ApiResponse<CharacterDraft[]>>('/api/characters/extract', { projectId });
}

/**
 * 编辑角色信息
 */
export async function updateCharacter(
  projectId: string,
  charId: string,
  data: Partial<CharacterListItem>
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(`/api/projects/${projectId}/characters/${charId}`, data);
}

/**
 * 删除角色（逻辑删除）
 */
export async function deleteCharacter(
  projectId: string,
  charId: string,
): Promise<ApiResponse<void>> {
  return del<ApiResponse<void>>(`/api/projects/${projectId}/characters/${charId}`);
}

/**
 * 确认所有角色
 */
export async function confirmCharacters(projectId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/characters/confirm`);
}

/**
 * 获取角色生成状态详情
 */
export async function getCharacterStatus(projectId: string, charId: string): Promise<ApiResponse<CharacterStatus>> {
  return get<ApiResponse<CharacterStatus>>(`/api/projects/${projectId}/characters/${charId}`);
}

/**
 * 一键生成角色图片（表情 + 三视图）
 */
export async function generateAllImages(projectId: string, charId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/projects/${projectId}/characters/${charId}/generate/all`);
}

/**
 * 生成单项图片（表情 或 三视图）
 */
export async function generateImage(
  projectId: string,
  charId: string,
  type: 'expression' | 'threeView'
): Promise<ApiResponse<void>> {
  const path = type === 'expression'
    ? `/api/projects/${projectId}/characters/${charId}/generate/expression`
    : `/api/projects/${projectId}/characters/${charId}/generate/three-view`;
  return post<ApiResponse<void>>(path);
}

/**
 * 重试生成
 * @param type 'expression' 或 'threeview'
 */
export async function retryGeneration(
  projectId: string,
  charId: string,
  type: 'expression' | 'threeView'
): Promise<ApiResponse<void>> {
  const apiType = type === 'expression' ? 'expression' : 'threeview';
  return post<ApiResponse<void>>(`/api/projects/${projectId}/characters/${charId}/retry/${apiType}`);
}

/**
 * 设置角色视觉风格
 */
export async function setVisualStyle(
  charId: string,
  visualStyle: string
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(`/api/characters/${charId}/visual-style`, { visualStyle });
}
