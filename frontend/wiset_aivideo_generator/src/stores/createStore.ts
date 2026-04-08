import { create } from 'zustand';
import { getProjectStatus } from '../services/projectService';
import type { ProjectStatusInfo } from '../services/types/project.types';

interface CreateState {
  statusInfo: ProjectStatusInfo | null;
  isLoadingStatus: boolean;
  isPolling: boolean;
  /** 已触发过角色提取的项目 ID 集合，防止组件重挂载时重复提取 */
  extractCalledProjects: Set<string>;
  markExtractCalled: (projectId: string) => void;
  hasExtractCalled: (projectId: string) => boolean;
  startPolling: (projectId: string) => void;
  stopPolling: () => void;
  syncStatus: (projectId: string) => Promise<void>;
  canPerformAction: (action: string) => boolean;
  resetCreateFlow: () => void;
}

let pollingTimerId: ReturnType<typeof setTimeout> | null = null;

export function normalizeStatusInfo(raw: ProjectStatusInfo | Record<string, any>): ProjectStatusInfo {
  const data = raw as Record<string, any>;

  const generating = data.isGenerating ?? data.generating;
  const failed = data.isFailed ?? data.failed;
  const review = data.isReview ?? data.review;

  const reviewEpisodeId =
    data.panelReviewEpisodeId === null || data.panelReviewEpisodeId === undefined
      ? undefined
      : String(data.panelReviewEpisodeId);

  return {
    ...(data as ProjectStatusInfo),
    isGenerating: typeof generating === 'boolean' ? generating : false,
    isFailed: typeof failed === 'boolean' ? failed : false,
    isReview: typeof review === 'boolean' ? review : false,
    panelReviewEpisodeId: reviewEpisodeId,
  };
}

export const useCreateStore = create<CreateState>()((set, get) => ({
  statusInfo: null,
  isLoadingStatus: false,
  isPolling: false,
  extractCalledProjects: new Set<string>(),

  markExtractCalled: (projectId: string) => {
    set(state => {
      const next = new Set(state.extractCalledProjects);
      next.add(projectId);
      return { extractCalledProjects: next };
    });
  },

  hasExtractCalled: (projectId: string) => {
    return get().extractCalledProjects.has(projectId);
  },

  startPolling: (projectId: string) => {
    // TEMPORARILY DISABLED: store polling causes infinite re-render loops
    // Status updates are handled via SSE callbacks in Step4Production
    return;
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
      if ((response.code === 0 || response.code === 200) && response.data) {
        set({ statusInfo: normalizeStatusInfo(response.data) });
      }
    } catch (error) {
      console.error('Failed to sync project status', error);
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
      extractCalledProjects: new Set<string>(),
    });
  },
}));