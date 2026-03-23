import { create } from 'zustand';
import type { GridInfoResponse } from '../services/types/episode.types';

export interface PanelOverlay {
  characterName: string;
  sourceType: string;
  imageUrl: string;
  x: number;
  y: number;
  width: number;
  height: number;
  opacity: number;
  scale: number;
}

interface FusionState {
  gridInfo: GridInfoResponse | null;
  sceneGridImage: HTMLImageElement | null;
  currentPageIndex: number;
  allGridImages: Map<number, HTMLImageElement>;
  selectedPanelIndex: number | null;
  selectedCharacterIndex: number | null;
  panelOverlays: Map<number, PanelOverlay>;
  isSubmitting: boolean;
  isUploading: boolean;

  setGridInfo: (info: GridInfoResponse) => void;
  setSceneGridImage: (img: HTMLImageElement) => void;
  setCurrentPageIndex: (index: number) => void;
  setGridImage: (pageIndex: number, img: HTMLImageElement) => void;
  selectPanel: (index: number | null) => void;
  selectCharacter: (index: number | null) => void;
  assignCharacterToPanel: (panelIndex: number, charIndex: number) => void;
  removeOverlayFromPanel: (panelIndex: number) => void;
  updateOverlayProperty: (panelIndex: number, props: Partial<PanelOverlay>) => void;
  setSubmitting: (v: boolean) => void;
  setUploading: (v: boolean) => void;
  reset: () => void;
}

export const useFusionStore = create<FusionState>()((set, get) => ({
  gridInfo: null,
  sceneGridImage: null,
  currentPageIndex: 0,
  allGridImages: new Map(),
  selectedPanelIndex: null,
  selectedCharacterIndex: null,
  panelOverlays: new Map(),
  isSubmitting: false,
  isUploading: false,

  setGridInfo: (info) => set({ gridInfo: info }),
  setSceneGridImage: (img) => set({ sceneGridImage: img }),
  setCurrentPageIndex: (index) => {
    const { allGridImages } = get();
    const img = allGridImages.get(index) || null;
    set({ currentPageIndex: index, sceneGridImage: img, selectedPanelIndex: null, panelOverlays: new Map() });
  },
  setGridImage: (pageIndex, img) => {
    set((state) => {
      const newMap = new Map(state.allGridImages);
      newMap.set(pageIndex, img);
      return {
        allGridImages: newMap,
        sceneGridImage: state.currentPageIndex === pageIndex ? img : state.sceneGridImage,
      };
    });
  },
  selectPanel: (index) => set({ selectedPanelIndex: index }),
  selectCharacter: (index) => set({ selectedCharacterIndex: index }),
  setSubmitting: (v) => set({ isSubmitting: v }),
  setUploading: (v) => set({ isUploading: v }),

  assignCharacterToPanel: (panelIndex, charIndex) => {
    const { gridInfo } = get();
    if (!gridInfo) return;
    const char = gridInfo.characterReferences[charIndex];
    if (!char) return;

    const url = char.threeViewGridUrl || char.expressionGridUrl || '';
    if (!url) return;

    const overlay: PanelOverlay = {
      characterName: char.characterName,
      sourceType: 'threeView',
      imageUrl: url,
      x: 0,
      y: 0,
      width: gridInfo.panelWidth,
      height: gridInfo.panelHeight,
      opacity: 0.7,
      scale: 0.3,
    };

    set((state) => {
      const newOverlays = new Map(state.panelOverlays);
      newOverlays.set(panelIndex, overlay);
      return { panelOverlays: newOverlays };
    });
  },

  removeOverlayFromPanel: (panelIndex) => {
    set((state) => {
      const newOverlays = new Map(state.panelOverlays);
      newOverlays.delete(panelIndex);
      return { panelOverlays: newOverlays };
    });
  },

  updateOverlayProperty: (panelIndex, props) => {
    set((state) => {
      const newOverlays = new Map(state.panelOverlays);
      const existing = newOverlays.get(panelIndex);
      if (existing) {
        newOverlays.set(panelIndex, { ...existing, ...props });
      }
      return { panelOverlays: newOverlays };
    });
  },

  reset: () => set({
    gridInfo: null,
    sceneGridImage: null,
    currentPageIndex: 0,
    allGridImages: new Map(),
    selectedPanelIndex: null,
    selectedCharacterIndex: null,
    panelOverlays: new Map(),
    isSubmitting: false,
    isUploading: false,
  }),
}));

/** 自动分配角色到面板 */
export function autoAssignCharacters(gridInfo: GridInfoResponse): Map<number, PanelOverlay> {
  const { gridColumns, gridRows, characterReferences, panelWidth, panelHeight } = gridInfo;
  const totalPanels = gridColumns * gridRows;
  const overlays = new Map<number, PanelOverlay>();

  if (characterReferences.length === 0) return overlays;

  const centerPanel = Math.floor(gridRows / 2) * gridColumns + Math.floor(gridColumns / 2);
  const sortedPanels = Array.from({ length: totalPanels }, (_, i) => i)
    .sort((a, b) => {
      if (a === centerPanel) return -1;
      if (b === centerPanel) return 1;
      return 0;
    });

  characterReferences.forEach((char, charIndex) => {
    const panelIndex = sortedPanels[charIndex] ?? charIndex;
    const url = char.threeViewGridUrl || char.expressionGridUrl || '';
    if (!url) return;

    overlays.set(panelIndex, {
      characterName: char.characterName,
      sourceType: 'threeView',
      imageUrl: url,
      x: 0,
      y: 0,
      width: panelWidth,
      height: panelHeight,
      opacity: 0.7,
      scale: 0.3,
    });
  });

  return overlays;
}
