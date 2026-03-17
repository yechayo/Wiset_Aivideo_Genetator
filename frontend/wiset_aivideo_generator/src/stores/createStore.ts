import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { getProjectStatus } from '../services/projectService';
import type { ProjectStatusInfo } from '../services/types/project.types';

interface CreateState {
  // 当前步骤
  currentStep: number;

  // 已完成的步骤
  completedSteps: number[];

  // 后端状态信息
  statusInfo: ProjectStatusInfo | null;

  // 是否正在加载状态
  isLoadingStatus: boolean;

  // 设置当前步骤
  setCurrentStep: (step: number) => void;

  // 添加完成的步骤
  addCompletedStep: (step: number) => void;

  // 检查步骤是否已完成
  isStepCompleted: (step: number) => boolean;

  // 获取下一个可跳转的步骤
  getNextStep: () => number;

  // 重置创建流程
  resetCreateFlow: () => void;

  // 从后端同步状态
  syncStatus: (projectId: string) => Promise<void>;

  // 检查是否可执行操作
  canPerformAction: (action: string) => boolean;

  // 根据后端状态更新步骤
  applyStatusInfo: (statusInfo: ProjectStatusInfo) => void;
}

export const useCreateStore = create<CreateState>()(
  persist(
    (set, get) => ({
      currentStep: 1,
      completedSteps: [],
      statusInfo: null,
      isLoadingStatus: false,

      setCurrentStep: (step) => {
        set({ currentStep: step });
      },

      addCompletedStep: (step) => {
        set((state) => {
          if (state.completedSteps.includes(step)) return state;
          return {
            completedSteps: [...state.completedSteps, step],
          };
        });
      },

      isStepCompleted: (step) => {
        return get().completedSteps.includes(step);
      },

      getNextStep: () => {
        const completed = get().completedSteps;
        // 找到最小的未完成步骤
        for (let i = 1; i <= 5; i++) {
          if (!completed.includes(i)) return i;
        }
        return 5; // 全部完成
      },

      resetCreateFlow: () => {
        set({
          currentStep: 1,
          completedSteps: [],
          statusInfo: null,
        });
      },

      /**
       * 从后端同步项目状态
       * 更新步骤和完成状态，确保前后端一致
       */
      syncStatus: async (projectId: string) => {
        set({ isLoadingStatus: true });
        try {
          const response = await getProjectStatus(projectId);
          if (response.code === 200 && response.data) {
            get().applyStatusInfo(response.data);
          }
        } catch (error) {
          console.error('同步项目状态失败:', error);
        } finally {
          set({ isLoadingStatus: false });
        }
      },

      /**
       * 检查当前状态是否可以执行指定操作
       */
      canPerformAction: (action: string) => {
        const statusInfo = get().statusInfo;
        if (!statusInfo) return false;
        return statusInfo.availableActions.includes(action);
      },

      /**
       * 根据后端返回的状态信息更新本地步骤状态
       */
      applyStatusInfo: (statusInfo: ProjectStatusInfo) => {
        set({
          statusInfo,
          currentStep: statusInfo.currentStep,
          completedSteps: statusInfo.completedSteps,
        });
      },
    }),
    {
      name: 'wiset-create-storage', // localStorage key
    }
  )
);