import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

/**
 * 用户信息接口
 */
export interface UserInfo {
  userId: number;
  username: string;
}

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  tokenExpiresAt: number | null; // token 过期时间戳（毫秒）
  userInfo: UserInfo | null;
  setAuthData: (accessToken: string, refreshToken: string, userInfo: UserInfo, expiresIn?: number) => void;
  updateAccessToken: (accessToken: string, expiresIn?: number) => void;
  clearAuth: () => void;
  isAuthenticated: () => boolean;
  getAccessToken: () => string | null;
  getRefreshToken: () => string | null;
  getUserInfo: () => UserInfo | null;
  isTokenExpiring: () => boolean; // 检查 token 是否即将过期（5分钟内）
  getTokenExpiresAt: () => number | null;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      tokenExpiresAt: null,
      userInfo: null,

      setAuthData: (accessToken, refreshToken, userInfo, expiresIn) => {
        const expiresAt = expiresIn ? Date.now() + expiresIn * 1000 : null;
        set({ accessToken, refreshToken, userInfo, tokenExpiresAt: expiresAt });
      },

      updateAccessToken: (accessToken, expiresIn) => {
        const currentExpiresAt = expiresIn ? Date.now() + expiresIn * 1000 : get().tokenExpiresAt;
        set({ accessToken, tokenExpiresAt: currentExpiresAt });
      },

      clearAuth: () => {
        set({ accessToken: null, refreshToken: null, userInfo: null, tokenExpiresAt: null });
      },

      isAuthenticated: () => {
        return !!get().accessToken;
      },

      getAccessToken: () => {
        return get().accessToken;
      },

      getRefreshToken: () => {
        return get().refreshToken;
      },

      getUserInfo: () => {
        return get().userInfo;
      },

      isTokenExpiring: () => {
        const expiresAt = get().tokenExpiresAt;
        if (!expiresAt) return false;
        // 检查是否在 5 分钟内过期
        return Date.now() > expiresAt - 5 * 60 * 1000;
      },

      getTokenExpiresAt: () => {
        return get().tokenExpiresAt;
      },
    }),
    {
      name: 'wiset-auth',
      storage: createJSONStorage(() => sessionStorage),
    }
  )
);
