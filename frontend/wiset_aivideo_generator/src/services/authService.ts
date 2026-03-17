/**
 * 认证相关API服务
 */

import { post } from './apiClient';
import { authClient } from './apiClient';
import type {
  RegisterRequest,
  LoginRequest,
  AuthResponse,
  ApiResponse
} from './types/auth.types';

// Mock 模式开关（可通过环境变量控制）
const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true' || false;

/**
 * 生成 mock token
 */
function generateMockToken(): string {
  return `mock_token_${Date.now()}_${Math.random().toString(36).substring(2)}`;
}

/**
 * 模拟延迟
 */
function mockDelay<T>(data: T, delay: number = 800): Promise<T> {
  return new Promise((resolve) => {
    setTimeout(() => resolve(data), delay);
  });
}

/**
 * Mock 数据：用户注册
 */
function mockRegister(data: RegisterRequest): Promise<ApiResponse<AuthResponse>> {
  return mockDelay({
    code: 0,
    message: '注册成功',
    data: {
      accessToken: generateMockToken(),
      refreshToken: `mock_refresh_${Date.now()}`,
      username: data.username,
      userId: Math.floor(Math.random() * 10000) + 1,
    },
  });
}

/**
 * Mock 数据：用户登录
 */
function mockLogin(data: LoginRequest): Promise<ApiResponse<AuthResponse>> {
  // 模拟登录验证（允许任意非空用户名密码登录）
  if (!data.username || !data.password) {
    return mockDelay({
      code: 1,
      message: '用户名或密码不能为空',
    });
  }

  return mockDelay({
    code: 0,
    message: '登录成功',
    data: {
      accessToken: generateMockToken(),
      refreshToken: `mock_refresh_${Date.now()}`,
      username: data.username,
      userId: Math.floor(Math.random() * 10000) + 1,
    },
  });
}

/**
 * Mock 数据：刷新访问令牌
 */
function mockRefreshAccessToken(): Promise<ApiResponse<{ accessToken: string }>> {
  return mockDelay({
    code: 0,
    message: '刷新成功',
    data: {
      accessToken: generateMockToken(),
    },
  });
}

/**
 * Mock 数据：用户登出
 */
function mockLogout(): Promise<ApiResponse<void>> {
  return mockDelay({
    code: 0,
    message: '登出成功',
  });
}

/**
 * 用户注册
 * @param data 注册信息
 * @returns 注册响应，包含token和用户信息
 */
export async function register(data: RegisterRequest): Promise<ApiResponse<AuthResponse>> {
  if (USE_MOCK) {
    return mockRegister(data);
  }
  return post<ApiResponse<AuthResponse>>('/api/auth/register', data);
}

/**
 * 用户登录
 * @param data 登录信息
 * @returns 登录响应，包含token和用户信息
 */
export async function login(data: LoginRequest): Promise<ApiResponse<AuthResponse>> {
  if (USE_MOCK) {
    return mockLogin(data);
  }
  return post<ApiResponse<AuthResponse>>('/api/auth/login', data);
}

/**
 * 刷新访问令牌
 * @param refreshToken 刷新令牌
 * @returns 新的访问令牌
 */
export async function refreshAccessToken(refreshToken: string): Promise<ApiResponse<{ accessToken: string }>> {
  if (USE_MOCK) {
    return mockRefreshAccessToken();
  }
  // 使用 authClient 避免触发 token 刷新逻辑
  const response = await authClient.post<ApiResponse<{ accessToken: string }>>('/api/auth/refresh', { refreshToken });
  return response.data;
}

/**
 * 用户登出
 */
export async function logout(): Promise<ApiResponse<void>> {
  if (USE_MOCK) {
    return mockLogout();
  }
  return post<ApiResponse<void>>('/api/auth/logout');
}
