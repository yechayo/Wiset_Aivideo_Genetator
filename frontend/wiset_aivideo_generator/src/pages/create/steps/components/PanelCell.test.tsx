import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import PanelCell from './PanelCell';
import type { PanelState } from '../../../../services/types/episode.types';

// 从渲染容器中查询元素的辅助函数
function queryVideo(container: HTMLElement) {
  return container.querySelector('video');
}

describe('PanelCell', () => {
  const mockOnGenerateVideo = vi.fn();

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('显示面板状态', () => {
    it('显示面板索引号', () => {
      const panel: PanelState = {
        panelIndex: 2,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'pending',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('#3')).toBeInTheDocument();
    });

    it('显示"待生成"状态', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'pending',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('待生成')).toBeInTheDocument();
    });

    it('显示"生成中"状态', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'completed',
        fusionUrl: 'http://example.com/fusion.jpg',
        promptText: null,
        videoStatus: 'generating',
        videoUrl: null,
        videoTaskId: 'task-123',
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('生成中')).toBeInTheDocument();
    });

    it('显示"已完成"状态', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'completed',
        fusionUrl: 'http://example.com/fusion.jpg',
        promptText: null,
        videoStatus: 'completed',
        videoUrl: 'http://example.com/video.mp4',
        videoTaskId: 'task-123',
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('已完成')).toBeInTheDocument();
    });

    it('显示"失败"状态', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'completed',
        fusionUrl: 'http://example.com/fusion.jpg',
        promptText: null,
        videoStatus: 'failed',
        videoUrl: null,
        videoTaskId: 'task-failed',
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('失败')).toBeInTheDocument();
    });
  });

  describe('根据状态显示内容', () => {
    it('视频完成时显示视频元素', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'completed',
        fusionUrl: 'http://example.com/fusion.jpg',
        promptText: null,
        videoStatus: 'completed',
        videoUrl: 'http://example.com/video.mp4',
        videoTaskId: 'task-123',
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      const { container } = render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      const video = container.querySelector('video');
      expect(video).toBeInTheDocument();
      expect(video).toHaveAttribute('src', 'http://example.com/video.mp4');
    });

    it('无视频时显示融合图', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'completed',
        fusionUrl: 'http://example.com/fusion.jpg',
        promptText: 'A sunny beach',
        videoStatus: 'pending',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      const img = screen.queryByRole('img');
      expect(img).toBeInTheDocument();
      expect(img).toHaveAttribute('src', 'http://example.com/fusion.jpg');
    });

    it('生成中状态显示加载动画', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'completed',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'generating',
        videoUrl: null,
        videoTaskId: 'task-123',
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('视频生成中...')).toBeInTheDocument();
    });

    it('待生成状态显示等待提示', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'pending',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('等待生成')).toBeInTheDocument();
    });

    it('失败状态显示错误提示', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'failed',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('生成失败')).toBeInTheDocument();
    });
  });

  describe('显示场景信息', () => {
    it('显示场景描述', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'pending',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: '一个阳光明媚的海滩',
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('场景:')).toBeInTheDocument();
      expect(screen.getByText('一个阳光明媚的海滩')).toBeInTheDocument();
    });

    it('显示景别', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'pending',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: '中景',
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('景别:')).toBeInTheDocument();
      expect(screen.getByText('中景')).toBeInTheDocument();
    });

    it('显示对白', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'pending',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: '你好，世界！',
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('对白:')).toBeInTheDocument();
      expect(screen.getByText('你好，世界！')).toBeInTheDocument();
    });

    it('同时显示所有场景信息', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'pending',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: '海滩',
        shotType: '远景',
        dialogue: '看那大海！',
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      expect(screen.getByText('场景:')).toBeInTheDocument();
      expect(screen.getByText('海滩')).toBeInTheDocument();
      expect(screen.getByText('景别:')).toBeInTheDocument();
      expect(screen.getByText('远景')).toBeInTheDocument();
      expect(screen.getByText('对白:')).toBeInTheDocument();
      expect(screen.getByText('看那大海！')).toBeInTheDocument();
    });
  });

  describe('失败时显示重试按钮', () => {
    it('失败状态且提供回调时显示重试按钮', () => {
      const panel: PanelState = {
        panelIndex: 5,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'failed',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      const retryButton = screen.getByRole('button', { name: '重试' });
      expect(retryButton).toBeInTheDocument();
    });

    it('点击重试按钮调用回调', () => {
      const panel: PanelState = {
        panelIndex: 3,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'failed',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      const retryButton = screen.getByRole('button', { name: '重试' });
      retryButton.click();

      expect(mockOnGenerateVideo).toHaveBeenCalledTimes(1);
      expect(mockOnGenerateVideo).toHaveBeenCalledWith(3);
    });

    it('非失败状态不显示重试按钮', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'completed',
        videoUrl: 'http://example.com/video.mp4',
        videoTaskId: 'task-123',
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} onGenerateVideo={mockOnGenerateVideo} />);

      const retryButton = screen.queryByRole('button', { name: '重试' });
      expect(retryButton).not.toBeInTheDocument();
    });

    it('未提供回调时不显示重试按钮', () => {
      const panel: PanelState = {
        panelIndex: 0,
        fusionStatus: 'pending',
        fusionUrl: null,
        promptText: null,
        videoStatus: 'failed',
        videoUrl: null,
        videoTaskId: null,
        sceneDescription: null,
        shotType: null,
        dialogue: null,
        panelId: null,
      };

      render(<PanelCell panel={panel} />);

      const retryButton = screen.queryByRole('button', { name: '重试' });
      expect(retryButton).not.toBeInTheDocument();
    });
  });
});
