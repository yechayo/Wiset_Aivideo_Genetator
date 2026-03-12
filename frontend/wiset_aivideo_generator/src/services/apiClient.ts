/**
 * API客户端基础配置
 */

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

/**
 * 通用API请求函数
 */
async function request<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<T> {
  const url = `${API_BASE_URL}${endpoint}`;

  const config: RequestInit = {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options.headers,
    },
  };

  try {
    const response = await fetch(url, config);

    // 处理非JSON响应（如204 No Content）
    if (response.status === 204) {
      return undefined as T;
    }

    const data = await response.json();

    if (!response.ok) {
      throw new Error(data.message || `HTTP Error: ${response.status}`);
    }

    return data;
  } catch (error) {
    if (error instanceof Error) {
      throw error;
    }
    throw new Error('请求失败，请稍后重试');
  }
}

/**
 * GET请求
 */
export function get<T>(endpoint: string, token?: string): Promise<T> {
  return request<T>(endpoint, {
    method: 'GET',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

/**
 * POST请求
 */
export function post<T>(endpoint: string, data?: any, token?: string): Promise<T> {
  return request<T>(endpoint, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * PUT请求
 */
export function put<T>(endpoint: string, data?: any, token?: string): Promise<T> {
  return request<T>(endpoint, {
    method: 'PUT',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * DELETE请求
 */
export function del<T>(endpoint: string, token?: string): Promise<T> {
  return request<T>(endpoint, {
    method: 'DELETE',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

export { API_BASE_URL };
