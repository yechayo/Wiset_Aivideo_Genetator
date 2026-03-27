import { create } from 'zustand';
import { getProjectStatus } from '../services/projectService';
import type { ProjectStatusInfo } from '../services/types/project.types';

interface CreateState {
  statusInfo: ProjectStatusInfo | null;
  isLoadingStatus: boolean;
  isPolling: boolean;
  startPolling: (projectId: string) => void;
  stopPolling: () => void;
  syncStatus: (projectId: string) => Promise<void>;
  canPerformAction: (action: string) => boolean;
  resetCreateFlow: () => void;
}

let pollingTimerId: ReturnType<typeof setTimeout> | null = null;

function normalizeStatusInfo(raw: ProjectStatusInfo | Record<string, any>): ProjectStatusInfo {
  const data = raw as Record<string, any>;
  const statusCode = typeof data.statusCode === 'string' ? data.statusCode : '';

  const generating = data.isGenerating ?? data.generating;
  const failed = data.isFailed ?? data.failed;
  const review = data.isReview ?? data.review;

  const fallbackIsGenerating = statusCode.endsWith('_GENERATING') || statusCode === 'PRODUCING';
  const fallbackIsFailed = statusCode.endsWith('_FAILED');
  const fallbackIsReview = statusCode.endsWith('_REVIEW');

  const reviewEpisodeId =
    data.panelReviewEpisodeId === null || data.panelReviewEpisodeId === undefined
      ? undefined
      : String(data.panelReviewEpisodeId);

  return {
    ...(data as ProjectStatusInfo),
    isGenerating: typeof generating === 'boolean' ? generating : fallbackIsGenerating,
    isFailed: typeof failed === 'boolean' ? failed : fallbackIsFailed,
    isReview: typeof review === 'boolean' ? review : fallbackIsReview,
    panelReviewEpisodeId: reviewEpisodeId,
  };
}

export const useCreateStore = create<CreateState>()((set, get) => ({
  statusInfo: null,
  isLoadingStatus: false,
  isPolling: false,

  startPolling: (projectId: string) => {
    if (get().isPolling && get().statusInfo?.projectId === projectId) return;

    set({ isPolling: true });

    const poll = async () => {
      if (!get().isPolling) return;

      try {
        const response = await getProjectStatus(projectId);
        if ((response.code === 0 || response.code === 200) && response.data) {
          set({ statusInfo: normalizeStatusInfo(response.data) });
        }
      } catch (error) {
        console.error('Failed to poll project status', error);
      }

      if (!get().isPolling) return;
      const interval = get().statusInfo?.isGenerating ? 3000 : 5000;
      pollingTimerId = setTimeout(poll, interval);
    };

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
    });
  },
}));