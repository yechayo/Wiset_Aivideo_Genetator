/**
 * 认证相关API服务
 */

import { post } from './apiClient';
import type {
  RegisterRequest,
  LoginRequest,
  AuthResponse,
  ApiResponse
} from './types/auth.types';

/**
 * 用户注册
 * @param data 注册信息
 * @returns 注册响应，包含token和用户信息
 */
export async function register(data: RegisterRequest): Promise<ApiResponse<AuthResponse>> {
  return post<ApiResponse<AuthResponse>>('/api/auth/register', data);
}

/**
 * 用户登录
 * @param data 登录信息
 * @returns 登录响应，包含token和用户信息
 */
export async function login(data: LoginRequest): Promise<ApiResponse<AuthResponse>> {
  return post<ApiResponse<AuthResponse>>('/api/auth/login', data);
}

/**
 * 刷新访问令牌
 * @param refreshToken 刷新令牌
 * @returns 新的访问令牌
 */
export async function refreshAccessToken(refreshToken: string): Promise<ApiResponse<{ accessToken: string }>> {
  return post<ApiResponse<{ accessToken: string }>>('/api/auth/refresh', { refreshToken });
}

/**
 * 用户登出
 * @param token 访问令牌
 */
export async function logout(token: string): Promise<ApiResponse<void>> {
  return post<ApiResponse<void>>('/api/auth/logout', undefined, token);
}
