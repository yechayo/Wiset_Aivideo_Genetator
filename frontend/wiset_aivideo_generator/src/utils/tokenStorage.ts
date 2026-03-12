/**
 * Token存储管理工具
 */

const ACCESS_TOKEN_KEY = 'access_token';
const REFRESH_TOKEN_KEY = 'refresh_token';
const USER_INFO_KEY = 'user_info';

/**
 * 用户信息接口
 */
export interface UserInfo {
  userId: number;
  username: string;
}

/**
 * 存储访问令牌
 */
export function setAccessToken(token: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, token);
}

/**
 * 获取访问令牌
 */
export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

/**
 * 存储刷新令牌
 */
export function setRefreshToken(token: string): void {
  localStorage.setItem(REFRESH_TOKEN_KEY, token);
}

/**
 * 获取刷新令牌
 */
export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

/**
 * 存储用户信息
 */
export function setUserInfo(userInfo: UserInfo): void {
  localStorage.setItem(USER_INFO_KEY, JSON.stringify(userInfo));
}

/**
 * 获取用户信息
 */
export function getUserInfo(): UserInfo | null {
  const data = localStorage.getItem(USER_INFO_KEY);
  if (!data) return null;
  try {
    return JSON.parse(data);
  } catch {
    return null;
  }
}

/**
 * 清除所有认证信息
 */
export function clearAuth(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_INFO_KEY);
}

/**
 * 检查是否已登录
 */
export function isAuthenticated(): boolean {
  return !!getAccessToken();
}

/**
 * 存储完整的认证响应
 */
export function setAuthData(accessToken: string, refreshToken: string, userInfo: UserInfo): void {
  setAccessToken(accessToken);
  setRefreshToken(refreshToken);
  setUserInfo(userInfo);
}
