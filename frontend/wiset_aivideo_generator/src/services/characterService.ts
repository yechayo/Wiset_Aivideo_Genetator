/**
 * 角色相关 API 服务
 */

import { get, post, put } from './apiClient';
import type { ApiResponse } from './types/auth.types';
import type { CharacterDraft, CharacterStatus } from './types/project.types';

/**
 * 获取项目角色列表
 */
export async function getCharacters(projectId: string): Promise<ApiResponse<CharacterDraft[]>> {
  return get<ApiResponse<CharacterDraft[]>>(`/api/characters?projectId=${projectId}`);
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
  charId: string,
  data: Partial<CharacterDraft>
): Promise<ApiResponse<void>> {
  return put<ApiResponse<void>>(`/api/characters/${charId}`, data);
}

/**
 * 确认所有角色
 */
export async function confirmCharacters(projectId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>('/api/characters/confirm', { projectId });
}

/**
 * 获取角色生成状态
 */
export async function getCharacterStatus(charId: string): Promise<ApiResponse<CharacterStatus>> {
  return get<ApiResponse<CharacterStatus>>(`/api/characters/${charId}/status`);
}

/**
 * 一键生成角色图片（表情 + 三视图）
 */
export async function generateAllImages(charId: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/characters/${charId}/generate-all`);
}

/**
 * 重试生成
 * @param type 'expression' 或 'threeView'
 */
export async function retryGeneration(
  charId: string,
  type: 'expression' | 'threeView'
): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>(`/api/characters/${charId}/retry/${type}`);
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
