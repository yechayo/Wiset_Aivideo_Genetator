import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface CreateState {
  // 当前步骤
  currentStep: number;

  // 已完成的步骤
  completedSteps: number[];

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
}

export const useCreateStore = create<CreateState>()(
  persist(
    (set, get) => ({
      currentStep: 1,
      completedSteps: [],

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
        });
      },
    }),
    {
      name: 'wiset-create-storage', // localStorage key
    }
  )
);
