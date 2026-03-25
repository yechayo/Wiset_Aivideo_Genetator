/**
 * 创建流程步骤配置
 */

import type { Step } from '../types';

/**
 * 创建流程的6个步骤
 */
export const CREATE_STEPS: Step[] = [
  { id: 1, label: '创意输入', description: '输入故事创意和生成参数' },
  { id: 2, label: '剧本编辑', description: '编辑AI生成的剧本' },
  { id: 3, label: '角色配置', description: '配置角色形象' },
  { id: 4, label: '素材生成', description: '生成角色素材图片' },
  { id: 5, label: '视频生产', description: '管理分镜、四宫格和视频生成' },
  { id: 6, label: '最终合成', description: '视频合成与导出' },
];
