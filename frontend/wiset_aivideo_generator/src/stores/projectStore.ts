import { create } from 'zustand';
import type { Project } from '../services';

interface ProjectState {
  // 当前正在编辑/创建的项目
  currentProject: Project | null;

  // 设置当前项目
  setCurrentProject: (project: Project | null) => void;

  // 更新当前项目
  updateCurrentProject: (updates: Partial<Project>) => void;

  // 清除当前项目
  clearCurrentProject: () => void;

  // 获取项目 ID
  getProjectId: () => string | null;
}

export const useProjectStore = create<ProjectState>((set, get) => ({
  currentProject: null,

  setCurrentProject: (project) => {
    set({ currentProject: project });
  },

  updateCurrentProject: (updates) => {
    const current = get().currentProject;
    if (current) {
      set({ currentProject: { ...current, ...updates } });
    }
  },

  clearCurrentProject: () => {
    set({ currentProject: null });
  },

  getProjectId: () => {
    const project = get().currentProject;
    if (!project) return null;
    // 优先使用 projectId（字符串），回退到 id（数字）
    return project.projectId || (project.id ? String(project.id) : null);
  },
}));
