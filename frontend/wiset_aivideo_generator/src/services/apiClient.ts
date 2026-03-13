/**
 * API客户端基础配置 - 使用 axios
 */

import axios, { AxiosError } from 'axios';
import type { InternalAxiosRequestConfig, AxiosResponse } from 'axios';
import { useAuthStore } from '../stores/authStore';
import { refreshAccessToken } from './authService';
import type { ApiResponse } from './types/auth.types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

/**
 * Token 刷新状态管理
 */
let isRefreshing = false;
let failedQueue: Array<{ resolve: (value: string) => void; reject: (reason: any) => void }> = [];

/**
 * 处理队列中的请求
 */
const processQueue = (error: Error | null, token: string | null = null) => {
  failedQueue.forEach(prom => {
    if (error) {
      prom.reject(error);
    } else if (token) {
      prom.resolve(token);
    } else {
      prom.reject(new Error('Token 刷新失败'));
    }
  });
  failedQueue = [];
};

/**
 * 创建 axios 实例
 */
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

/**
 * 请求拦截器 - 自动添加 token
 */
apiClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = useAuthStore.getState().getAccessToken();
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error: AxiosError) => {
    return Promise.reject(error);
  }
);

/**
 * 响应拦截器 - 处理 401 和 token 刷新
 */
apiClient.interceptors.response.use(
  (response: AxiosResponse) => {
    return response.data;
  },
  async (error: AxiosError) => {
    const originalRequest = error.config as InternalAxiosRequestConfig & { _retry?: boolean };

    // 处理 401 错误
    if (error.response?.status === 401 && !originalRequest._retry) {
      const refreshToken = useAuthStore.getState().getRefreshToken();

      // 如果没有 refreshToken，直接登出
      if (!refreshToken) {
        useAuthStore.getState().clearAuth();
        window.location.href = '/login';
        return Promise.reject(new Error('未登录或登录已过期'));
      }

      // 如果正在刷新，将请求加入队列等待
      if (isRefreshing) {
        return new Promise<string>((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        })
          .then((newToken) => {
            if (originalRequest.headers) {
              originalRequest.headers.Authorization = `Bearer ${newToken}`;
            }
            return apiClient(originalRequest);
          })
          .catch((err) => {
            return Promise.reject(err);
          });
      }

      // 开始刷新流程
      originalRequest._retry = true;
      isRefreshing = true;

      try {
        // 调用刷新接口
        const refreshResult = await refreshAccessToken(refreshToken);

        if (refreshResult.code === 0 && refreshResult.data?.accessToken) {
          // 更新 store 中的 accessToken
          useAuthStore.getState().updateAccessToken(refreshResult.data.accessToken);

          // 处理队列中的请求
          processQueue(null, refreshResult.data.accessToken);

          // 重试当前请求
          if (originalRequest.headers) {
            originalRequest.headers.Authorization = `Bearer ${refreshResult.data.accessToken}`;
          }
          return apiClient(originalRequest);
        } else {
          throw new Error('刷新 token 失败');
        }
      } catch (refreshError) {
        // 刷新失败：清除认证信息并跳转登录
        processQueue(refreshError as Error, null);
        useAuthStore.getState().clearAuth();
        window.location.href = '/login';
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    // 处理其他错误
    const errorMessage = (error.response?.data as any)?.message || error.message || '请求失败，请稍后重试';
    return Promise.reject(new Error(errorMessage));
  }
);

/**
 * GET 请求
 */
export function get<T>(endpoint: string, config = {}): Promise<T> {
  return apiClient.get<T>(endpoint, config).then(res => res as unknown as T);
}

/**
 * POST 请求
 */
export function post<T>(endpoint: string, data?: any, config = {}): Promise<T> {
  return apiClient.post<T>(endpoint, data, config).then(res => res as unknown as T);
}

/**
 * PUT 请求
 */
export function put<T>(endpoint: string, data?: any, config = {}): Promise<T> {
  return apiClient.put<T>(endpoint, data, config).then(res => res as unknown as T);
}

/**
 * DELETE 请求
 */
export function del<T>(endpoint: string, config = {}): Promise<T> {
  return apiClient.delete<T>(endpoint, config).then(res => res as unknown as T);
}

export { API_BASE_URL };
export default apiClient;

/**
 * 判断 API 响应是否成功
 * Mock 模式使用 code === 0，真实 API 使用 code === 200
 */
export function isApiSuccess(response: ApiResponse): boolean {
  return response.code === 0 || response.code === 200;
}
