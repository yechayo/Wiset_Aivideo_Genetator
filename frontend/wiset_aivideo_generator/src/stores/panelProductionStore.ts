import { create } from 'zustand';
import type {
  PanelStageStatus,
  ProductionStage,
  PanelOverallStatus,
  PanelProductionStatusResponse,
} from '../services/types/episode.types';
import { getPanelProductionStatus } from '../services/panelProductionService';
import { isApiSuccess } from '../services/apiClient';

export interface PanelProductionState {
  panelIndex: number;
  episodeId: number;
  backgroundUrl: string | null;
  backgroundStatus: PanelStageStatus;
  fusionUrl: string | null;
  fusionStatus: PanelStageStatus;
  transitionUrl: string | null;
  transitionStatus: PanelStageStatus;
  videoUrl: string | null;
  videoStatus: PanelStageStatus;
  videoDuration: number | null;
  tailFrameUrl: string | null;
  overallStatus: PanelOverallStatus;
  currentStage: ProductionStage;
}

interface PanelProductionStore {
  // State
  panel: PanelProductionState | null;
  isLoading: boolean;
  isOperating: boolean;
  error: string | null;

  // Actions
  loadPanelState: (episodeId: number, panelIndex: number) => Promise<void>;
  updateStageStatus: (stage: ProductionStage, status: PanelStageStatus, url?: string | null) => void;
  setOperating: (operating: boolean) => void;
  setError: (error: string | null) => void;
  reset: () => void;
}

const initialPanelState: PanelProductionState = {
  panelIndex: -1,
  episodeId: 0,
  backgroundUrl: null,
  backgroundStatus: 'pending',
  fusionUrl: null,
  fusionStatus: 'pending',
  transitionUrl: null,
  transitionStatus: 'pending',
  videoUrl: null,
  videoStatus: 'pending',
  videoDuration: null,
  tailFrameUrl: null,
  overallStatus: 'pending',
  currentStage: 'background',
};

export const usePanelProductionStore = create<PanelProductionStore>()((set) => ({
  panel: null,
  isLoading: false,
  isOperating: false,
  error: null,

  loadPanelState: async (episodeId: number, panelIndex: number) => {
    set({ isLoading: true, error: null });
    try {
      const res = await getPanelProductionStatus(String(episodeId), panelIndex);
      if (isApiSuccess(res) && res.data) {
        const data: PanelProductionStatusResponse = res.data;
        set({
          panel: {
            panelIndex: data.panelIndex,
            episodeId,
            backgroundUrl: data.backgroundUrl,
            backgroundStatus: data.backgroundStatus,
            fusionUrl: data.fusionUrl,
            fusionStatus: data.fusionStatus,
            transitionUrl: data.transitionUrl,
            transitionStatus: data.transitionStatus,
            videoUrl: data.videoUrl,
            videoStatus: data.videoStatus,
            videoDuration: data.videoDuration,
            tailFrameUrl: data.tailFrameUrl,
            overallStatus: data.overallStatus,
            currentStage: data.currentStage,
          },
          isLoading: false,
        });
      } else {
        set({ panel: { ...initialPanelState, panelIndex, episodeId }, isLoading: false });
      }
    } catch (e: any) {
      set({ error: e.message || 'Failed to load panel state', isLoading: false });
    }
  },

  updateStageStatus: (stage: ProductionStage, status: PanelStageStatus, url?: string | null) => {
    set((state) => {
      if (!state.panel) return state;
      const panel = { ...state.panel };

      switch (stage) {
        case 'background':
          panel.backgroundStatus = status;
          if (url !== undefined) panel.backgroundUrl = url;
          break;
        case 'fusion':
          panel.fusionStatus = status;
          if (url !== undefined) panel.fusionUrl = url;
          break;
        case 'transition':
          panel.transitionStatus = status;
          if (url !== undefined) panel.transitionUrl = url;
          break;
        case 'video':
          panel.videoStatus = status;
          if (url !== undefined) panel.videoUrl = url;
          break;
      }

      // Recalculate overall status
      if (status === 'generating') {
        panel.overallStatus = 'in_progress';
      } else if (status === 'failed') {
        panel.overallStatus = 'failed';
      } else if (status === 'completed') {
        if (panel.videoStatus === 'completed') {
          panel.overallStatus = 'completed';
        } else {
          panel.overallStatus = 'in_progress';
        }
      }

      return { panel };
    });
  },

  setOperating: (operating: boolean) => set({ isOperating: operating }),
  setError: (error: string | null) => set({ error }),
  reset: () => set({ panel: null, isLoading: false, isOperating: false, error: null }),
}));