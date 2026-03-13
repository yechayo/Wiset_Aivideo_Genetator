/**
 * 项目相关类型定义
 */

/**
 * 剧本场景
 */
export interface Scene {
  id?: number;
  description: string;
  duration?: number;
  // 后续可扩展更多字段
}

/**
 * 剧本信息
 */
export interface Script {
  title?: string;
  content?: string;
  scenes?: Scene[];
  // 后续可扩展更多字段
}

/**
 * 创建项目请求参数
 */
export interface CreateProjectRequest {
  storyPrompt: string;
  genre: string;
  targetAudience: string;
  totalEpisodes: number;
  episodeDuration: number;
}

/**
 * 项目信息
 */
export interface Project {
  id?: number;
  storyPrompt: string;
  genre: string;
  targetAudience: string;
  totalEpisodes: number;
  episodeDuration: number;
  script?: Script;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 修订脚本请求参数（支持动态属性）
 */
export type ReviseScriptRequest = Record<string, string>;

/**
 * 修订脚本响应
 */
export interface ReviseScriptResponse {
  [key: string]: any;
}
