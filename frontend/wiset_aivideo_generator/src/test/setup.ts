import '@testing-library/jest-dom';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

// 每个测试后清理
afterEach(() => {
  cleanup();
});

// Mock CSS/LESS 模块
vi.mock('*.less', () => ({}));
vi.mock('*.css', () => ({}));

// Mock 图片资源
vi.mock('@/assets/*', () => ({}));
