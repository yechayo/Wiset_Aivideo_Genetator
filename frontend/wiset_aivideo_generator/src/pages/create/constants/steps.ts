/**
 * 创建流程步骤配置
 */

import type { Step } from '../types';

/**
 * 创建流程的5个步骤
 */
export const CREATE_STEPS: Step[] = [
  { id: 1, label: '创意与设定', description: '输入创意和设定' },
  { id: 2, label: '大纲与剧情', description: '生成大纲和剧情' },
  { id: 3, label: '角色与素材', description: '角色设定和图片' },
  { id: 4, label: '分镜生产', description: '脚本→九宫格→视频' },
  { id: 5, label: '合成与下载', description: '合成视频并下载' },
];
