// 认证相关类型定义

/**
 * 注册请求参数
 */
export interface RegisterRequest {
  username: string;
  password: string;
  email?: string;
}

/**
 * 登录请求参数
 */
export interface LoginRequest {
  username: string;
  password: string;
}

/**
 * 认证响应数据
 */
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  username: string;
  userId: number;
  expiresIn?: number; // token 过期时间（秒）
}

/**
 * API响应格式
 */
export interface ApiResponse<T = any> {
  code: number;
  message: string;
  data?: T;
}
