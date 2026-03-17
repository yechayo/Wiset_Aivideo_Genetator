import { create } from 'zustand';
import { getProjectStatus } from '../services/projectService';
import type { ProjectStatusInfo } from '../services/types/project.types';

interface CreateState {
  // 后端状态信息（唯一真理源）
  statusInfo: ProjectStatusInfo | null;

  // 是否正在加载状态
  isLoadingStatus: boolean;

  // 是否正在轮询
  isPolling: boolean;

  // 启动轮询
  startPolling: (projectId: string) => void;

  // 停止轮询
  stopPolling: () => void;

  // 从后端同步状态（单次）
  syncStatus: (projectId: string) => Promise<void>;

  // 检查是否可执行操作
  canPerformAction: (action: string) => boolean;

  // 重置创建流程
  resetCreateFlow: () => void;
}

let pollingTimerId: ReturnType<typeof setTimeout> | null = null;

export const useCreateStore = create<CreateState>()((set, get) => ({
  statusInfo: null,
  isLoadingStatus: false,
  isPolling: false,

  startPolling: (projectId: string) => {
    // 如果已在轮询同一个项目，不重复启动
    if (get().isPolling && get().statusInfo?.projectId === projectId) return;

    set({ isPolling: true });

    const poll = async () => {
      if (!get().isPolling) return;

      try {
        const response = await getProjectStatus(projectId);
        if (response.code === 200 && response.data) {
          set({ statusInfo: response.data });
        }
      } catch (error) {
        console.error('轮询项目状态失败:', error);
      }

      // 根据是否在生成中决定下次轮询间隔
      if (!get().isPolling) return;
      const interval = get().statusInfo?.isGenerating ? 3000 : 5000;
      pollingTimerId = setTimeout(poll, interval);
    };

    // 立即同步一次，然后开始轮询
    poll();
  },

  stopPolling: () => {
    set({ isPolling: false });
    if (pollingTimerId !== null) {
      clearTimeout(pollingTimerId);
      pollingTimerId = null;
    }
  },

  syncStatus: async (projectId: string) => {
    set({ isLoadingStatus: true });
    try {
      const response = await getProjectStatus(projectId);
      if (response.code === 200 && response.data) {
        set({ statusInfo: response.data });
      }
    } catch (error) {
      console.error('同步项目状态失败:', error);
    } finally {
      set({ isLoadingStatus: false });
    }
  },

  canPerformAction: (action: string) => {
    const statusInfo = get().statusInfo;
    if (!statusInfo) return false;
    return statusInfo.availableActions.includes(action);
  },

  resetCreateFlow: () => {
    get().stopPolling();
    set({
      statusInfo: null,
    });
  },
}));