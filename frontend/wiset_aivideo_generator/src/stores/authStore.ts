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
  userInfo: UserInfo | null;
  setAuthData: (accessToken: string, refreshToken: string, userInfo: UserInfo) => void;
  updateAccessToken: (accessToken: string) => void;
  clearAuth: () => void;
  isAuthenticated: () => boolean;
  getAccessToken: () => string | null;
  getRefreshToken: () => string | null;
  getUserInfo: () => UserInfo | null;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      userInfo: null,

      setAuthData: (accessToken, refreshToken, userInfo) => {
        set({ accessToken, refreshToken, userInfo });
      },

      updateAccessToken: (accessToken) => {
        set({ accessToken });
      },

      clearAuth: () => {
        set({ accessToken: null, refreshToken: null, userInfo: null });
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
    }),
    {
      name: 'wiset-auth',
      storage: createJSONStorage(() => sessionStorage),
    }
  )
);
