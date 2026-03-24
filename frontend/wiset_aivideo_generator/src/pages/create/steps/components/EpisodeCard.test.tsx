import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import EpisodeCard from './EpisodeCard';
import type { EpisodeCardData } from '../../../../services/types/episode.types';

const createMockEpisode = (overrides: Partial<EpisodeCardData> = {}): EpisodeCardData => ({
  id: 1,
  episodeNum: 1,
  title: '第一集',
  status: 'GENERATING',
  productionStatus: null,
  finalVideoUrl: null,
  errorMsg: null,
  storyboardJson: null,
  storyboardStatus: null,
  panelStates: [],
  sceneGridUrls: [],
  loading: false,
  loadError: null,
  ...overrides,
});

const createMockProps = (episode?: EpisodeCardData) => {
  const ep = episode || createMockEpisode();
  return {
    episode: ep,
    isCurrentReview: false,
    isLoadingStoryboard: false,
    storyboardStatusDesc: '',
    storyboardFailed: false,
    sceneGridUrls: [],
    onConfirm: vi.fn(),
    onRevise: vi.fn(),
    onRetry: vi.fn(),
    onLoadStoryboard: vi.fn(),
    onGenerateVideo: vi.fn(),
    onAutoContinue: vi.fn(),
    isGlobalSubmitting: false,
  };
};

describe('EpisodeCard', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('显示剧集信息', () => {
    it('显示剧集标题', () => {
      const props = createMockProps(createMockEpisode({ title: '测试剧集' }));

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('测试剧集')).toBeInTheDocument();
    });

    it('显示剧集编号', () => {
      const props = createMockProps(createMockEpisode({ episodeNum: 3 }));

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('EP 3')).toBeInTheDocument();
    });
  });

  describe('显示状态徽章', () => {
    it('DONE 状态显示"已完成"徽章', () => {
      const props = createMockProps(createMockEpisode({ status: 'DONE' }));

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('已完成')).toBeInTheDocument();
    });

    it('GENERATING 状态显示"生成中"徽章', () => {
      const props = createMockProps(createMockEpisode({ status: 'GENERATING' }));

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('生成中')).toBeInTheDocument();
    });

    it('FAILED 状态显示"失败"徽章', () => {
      const props = createMockProps(createMockEpisode({ status: 'FAILED' }));

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('失败')).toBeInTheDocument();
    });

    it('IN_PROGRESS 生产状态显示"生产中"徽章', () => {
      const props = createMockProps(
        createMockEpisode({ status: 'DONE', productionStatus: 'IN_PROGRESS' })
      );

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('生产中')).toBeInTheDocument();
    });
  });

  describe('模式切换', () => {
    it('默认显示审核模式', () => {
      const props = createMockProps();

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('分镜审核')).toBeInTheDocument();
    });

    it('可以切换到生产模式', async () => {
      const props = createMockProps(
        createMockEpisode({ status: 'DONE', productionStatus: 'IN_PROGRESS' })
      );

      render(<EpisodeCard {...props} />);

      const productionBtn = screen.getByText('视频生产');
      fireEvent.click(productionBtn);

      await waitFor(() => {
        expect(productionBtn.className).toContain('active');
      });
    });

    it('非生产状态下生产模式按钮被禁用', () => {
      const props = createMockProps(createMockEpisode({ status: 'DRAFT' }));

      render(<EpisodeCard {...props} />);

      const productionBtn = screen.getByText('视频生产');
      expect(productionBtn).toBeDisabled();
    });
  });

  describe('审核模式 - 分镜加载中', () => {
    it('显示加载中状态', () => {
      const props = createMockProps(
        createMockEpisode({ status: 'GENERATING' }),
      );
      props.isCurrentReview = true;
      props.isLoadingStoryboard = true;
      props.storyboardStatusDesc = '正在生成第 3 个场景...';

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('生成分镜中...')).toBeInTheDocument();
      expect(screen.getByText('正在生成第 3 个场景...')).toBeInTheDocument();
    });
  });

  describe('审核模式 - 分镜失败', () => {
    it('显示错误状态和重试按钮', () => {
      const props = createMockProps(
        createMockEpisode({ status: 'GENERATING' }),
      );
      props.isCurrentReview = true;
      props.storyboardFailed = true;
      props.storyboardStatusDesc = 'API 超时';

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('分镜生成失败')).toBeInTheDocument();
      expect(screen.getByText('API 超时')).toBeInTheDocument();
      expect(screen.getByText('重试')).toBeInTheDocument();
    });

    it('点击重试按钮调用回调', () => {
      const props = createMockProps(
        createMockEpisode({ status: 'GENERATING' }),
      );
      props.isCurrentReview = true;
      props.storyboardFailed = true;

      render(<EpisodeCard {...props} />);

      const retryBtn = screen.getByText('重试');
      fireEvent.click(retryBtn);

      expect(props.onRetry).toHaveBeenCalledTimes(1);
    });
  });

  describe('审核模式 - 分镜内容', () => {
    const storyboardJson = JSON.stringify({
      panels: [
        {
          scene: '海滩',
          characters: '张三',
          shot_size: '中景',
          camera_angle: '平视',
          dialogue: '你好！',
          effects: '无',
        },
        {
          scene: '城市',
          characters: '李四',
          shot_size: '远景',
          camera_angle: '俯视',
          dialogue: '再见！',
          effects: '淡入',
        },
      ],
    });

    it('显示分镜面板列表', () => {
      const props = createMockProps(
        createMockEpisode({ storyboardJson }),
      );

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('#1')).toBeInTheDocument();
      expect(screen.getByText('#2')).toBeInTheDocument();
    });

    it('显示面板详情', () => {
      const props = createMockProps(
        createMockEpisode({ storyboardJson }),
      );

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('海滩')).toBeInTheDocument();
      expect(screen.getByText('张三')).toBeInTheDocument();
      expect(screen.getByText('你好！')).toBeInTheDocument();
    });

    it('显示确认和修订按钮', () => {
      const props = createMockProps(
        createMockEpisode({ storyboardJson }),
      );

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('确认分镜')).toBeInTheDocument();
      expect(screen.getByText('修订')).toBeInTheDocument();
    });

    it('点击确认按钮调用回调', () => {
      const props = createMockProps(
        createMockEpisode({ storyboardJson }),
      );

      render(<EpisodeCard {...props} />);

      const confirmBtn = screen.getByText('确认分镜');
      fireEvent.click(confirmBtn);

      expect(props.onConfirm).toHaveBeenCalledTimes(1);
    });

    it('点击修订按钮显示输入框', () => {
      const props = createMockProps(
        createMockEpisode({ storyboardJson }),
      );

      render(<EpisodeCard {...props} />);

      const reviseBtn = screen.getByText('修订');
      fireEvent.click(reviseBtn);

      expect(screen.getByPlaceholderText('请描述需要修改的内容...')).toBeInTheDocument();
    });

    it('提交修订调用回调', async () => {
      const props = createMockProps(
        createMockEpisode({ storyboardJson }),
      );

      render(<EpisodeCard {...props} />);

      // 点击修订
      fireEvent.click(screen.getByText('修订'));

      // 输入修订内容
      const textarea = screen.getByPlaceholderText('请描述需要修改的内容...');
      fireEvent.change(textarea, { target: { value: '把第一个场景改成山区' } });

      // 提交修订
      const submitBtn = screen.getByText('提交修订');
      fireEvent.click(submitBtn);

      expect(props.onRevise).toHaveBeenCalledWith('把第一个场景改成山区');
    });

    it('取消修订隐藏输入框', () => {
      const props = createMockProps(
        createMockEpisode({ storyboardJson }),
      );

      render(<EpisodeCard {...props} />);

      // 点击修订
      fireEvent.click(screen.getByText('修订'));

      // 点击取消
      const cancelBtn = screen.getByText('取消');
      fireEvent.click(cancelBtn);

      expect(screen.queryByPlaceholderText('请描述需要修改的内容...')).not.toBeInTheDocument();
    });
  });

  describe('审核模式 - 空状态', () => {
    it('无分镜时显示空状态', () => {
      const props = createMockProps(
        createMockEpisode({ storyboardJson: null, status: 'DRAFT' }),
      );

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('暂无分镜数据')).toBeInTheDocument();
    });
  });

  describe('生产模式', () => {
    it('显示场景网格图', () => {
      const props = createMockProps(
        createMockEpisode({
          status: 'DONE',
          productionStatus: 'IN_PROGRESS',
        }),
      );
      props.sceneGridUrls = ['http://example.com/grid1.jpg', 'http://example.com/grid2.jpg'];

      render(<EpisodeCard {...props} />);

      // 切换到生产模式
      fireEvent.click(screen.getByText('视频生产'));

      expect(screen.getByText('场景网格图')).toBeInTheDocument();
      const images = screen.getAllByRole('img');
      expect(images.length).toBeGreaterThan(0);
    });

    it('显示面板状态', () => {
      const props = createMockProps(
        createMockEpisode({
          status: 'DONE',
          productionStatus: 'IN_PROGRESS',
          panelStates: [
            {
              panelIndex: 0,
              fusionStatus: 'completed',
              fusionUrl: 'http://example.com/fusion.jpg',
              promptText: null,
              videoStatus: 'completed',
              videoUrl: 'http://example.com/video.mp4',
              videoTaskId: 'task-123',
              sceneDescription: '海滩',
              shotType: '中景',
              dialogue: '你好',
              panelId: 'panel-1',
            },
          ],
        }),
      );

      render(<EpisodeCard {...props} />);

      // 切换到生产模式
      fireEvent.click(screen.getByText('视频生产'));

      expect(screen.getByText('面板状态')).toBeInTheDocument();
      expect(screen.getByText('#1')).toBeInTheDocument();
      // 使用 getAllByText 因为有多个"已完成"
      expect(screen.getAllByText('已完成').length).toBeGreaterThan(0);
    });

    it('显示一键自动化按钮', () => {
      const props = createMockProps(
        createMockEpisode({
          status: 'DONE',
          productionStatus: 'IN_PROGRESS',
          panelStates: [
            {
              panelIndex: 0,
              fusionStatus: 'completed',
              fusionUrl: null,
              promptText: null,
              videoStatus: 'pending',
              videoUrl: null,
              videoTaskId: null,
              sceneDescription: null,
              shotType: null,
              dialogue: null,
              panelId: null,
            },
          ],
        }),
      );

      render(<EpisodeCard {...props} />);

      // 切换到生产模式
      fireEvent.click(screen.getByText('视频生产'));

      expect(screen.getByText('一键自动化')).toBeInTheDocument();
    });

    it('点击一键自动化调用回调', () => {
      const props = createMockProps(
        createMockEpisode({
          status: 'DONE',
          productionStatus: 'IN_PROGRESS',
          panelStates: [
            {
              panelIndex: 0,
              fusionStatus: 'completed',
              fusionUrl: null,
              promptText: null,
              videoStatus: 'pending',
              videoUrl: null,
              videoTaskId: null,
              sceneDescription: null,
              shotType: null,
              dialogue: null,
              panelId: null,
            },
          ],
        }),
      );

      render(<EpisodeCard {...props} />);

      // 切换到生产模式
      fireEvent.click(screen.getByText('视频生产'));

      // 点击一键自动化
      const autoBtn = screen.getByText('一键自动化');
      fireEvent.click(autoBtn);

      expect(props.onAutoContinue).toHaveBeenCalledTimes(1);
    });
  });

  describe('最终视频', () => {
    it('显示最终视频', () => {
      const props = createMockProps(
        createMockEpisode({
          finalVideoUrl: 'http://example.com/final.mp4',
        }),
      );

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('最终视频')).toBeInTheDocument();

      const { container } = render(<EpisodeCard {...props} />);
      const video = container.querySelector('video');
      expect(video).toBeInTheDocument();
    });
  });

  describe('错误消息', () => {
    it('显示错误消息', () => {
      const props = createMockProps(
        createMockEpisode({ errorMsg: '生成失败，请重试' }),
      );

      render(<EpisodeCard {...props} />);

      expect(screen.getByText('生成失败，请重试')).toBeInTheDocument();
    });
  });
});
