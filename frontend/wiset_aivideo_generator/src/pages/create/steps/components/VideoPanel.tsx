import React, { useState, useEffect } from 'react';
import styles from './VideoPanel.module.less';
import type { SegmentPipelineStep } from '../types';

export interface VideoPanelProps {
  videoUrl: string | null;
  pipelineStep: SegmentPipelineStep;
  onGenerateVideo: (customPrompt?: string) => void;
  isGenerating?: boolean;
  videoTaskId?: string | null;
  videoOffPeak?: boolean | null;
  videoProgress?: number | null;     // 0-100
  videoCredits?: number | null;      // 积分消耗
  onLoadPrompt?: () => Promise<string>;  // 加载当前提示词
}

// Loading spinner component
const LoadingSpinner: React.FC = () => (
  <div className={styles.spinner}>
    <div className={styles.spinnerRing}></div>
  </div>
);

const VideoPanel: React.FC<VideoPanelProps> = ({
  videoUrl,
  pipelineStep,
  onGenerateVideo,
  isGenerating: isGeneratingProp,
  videoTaskId,
  videoOffPeak,
  videoProgress,
  videoCredits,
  onLoadPrompt
}) => {
  const canGenerate = pipelineStep === 'grid_approved';

  // 本地生成状态：当用户点击生成时立即设置为 true，确保 UI 立即显示加载动画
  const [localGenerating, setLocalGenerating] = useState(false);

  // 最终的 isGenerating 状态：优先使用本地状态，其次是外部状态，最后是 pipelineStep
  const isGenerating = localGenerating || isGeneratingProp || pipelineStep === 'video_generating';
  const isCompleted = pipelineStep === 'video_completed' && videoUrl;
  const isFailed = pipelineStep === 'video_failed';

  // 当 pipelineStep 变为完成或失败时，清除本地生成状态
  useEffect(() => {
    if (pipelineStep === 'video_completed' || pipelineStep === 'video_failed') {
      setLocalGenerating(false);
    }
  }, [pipelineStep]);

  // 模态框状态
  const [showModal, setShowModal] = useState(false);
  const [promptText, setPromptText] = useState('');
  const [loadingPrompt, setLoadingPrompt] = useState(false);
  const [activeTab, setActiveTab] = useState<'view' | 'edit'>('view');

  // 打开模态框
  const handleOpenModal = async (tab: 'view' | 'edit' = 'view') => {
    setActiveTab(tab);
    setShowModal(true);
    if (onLoadPrompt) {
      setLoadingPrompt(true);
      try {
        const prompt = await onLoadPrompt();
        setPromptText(prompt);
      } catch (e) {
        console.error('加载提示词失败:', e);
        setPromptText('');
      }
      setLoadingPrompt(false);
    }
  };

  // 提交生成
  const handleSubmit = () => {
    setShowModal(false);
    setLocalGenerating(true);  // 立即设置本地生成状态
    onGenerateVideo(activeTab === 'edit' ? promptText : undefined);
  };

  const renderContent = () => {
    if (isCompleted && videoUrl) {
      return (
        <div className={styles.videoWrapper}>
          <video
            className={styles.videoPlayer}
            controls
            src={videoUrl}
          >
            您的浏览器不支持视频播放。
          </video>
          {/* 视频信息 */}
          <div className={styles.videoInfo}>
            {videoOffPeak === true && (
              <span className={styles.infoTag} style={{ backgroundColor: '#10b981' }}>
                ✓ 错峰模式（积分优惠）
              </span>
            )}
            {videoTaskId && (
              <span className={styles.infoTag}>
                任务ID: {videoTaskId.slice(0, 8)}...
              </span>
            )}
          </div>
          {/* 重新生成按钮 */}
          <button
            className={styles.regenerateButton}
            onClick={() => handleOpenModal('edit')}
            title="修改提示词并重新生成"
          >
            ⟳ 重新生成
          </button>
        </div>
      );
    }

    if (isGenerating) {
      return (
        <div className={styles.placeholder}>
          <LoadingSpinner />
          <p className={styles.placeholderText}>
            视频生成中{videoProgress != null ? ` ${videoProgress}%` : '...'}
          </p>
          <div className={styles.generatingInfo}>
            {videoCredits != null && (
              <span className={styles.infoText}>💎 已消耗 {videoCredits} 积分</span>
            )}
            {videoOffPeak && (
              <span className={styles.infoText}>✓ 错峰模式（积分更优惠）</span>
            )}
            {videoTaskId && (
              <span className={styles.infoText}>任务ID: {videoTaskId.slice(0, 8)}...</span>
            )}
          </div>
        </div>
      );
    }

    if (isFailed) {
      return (
        <div className={styles.placeholder}>
          <p className={styles.errorText}>视频生成失败</p>
          <div className={styles.failedActions}>
            <button
              className={styles.retryButton}
              onClick={() => onGenerateVideo()}
            >
              ⟳ 重试
            </button>
            <button
              className={styles.editPromptButton}
              onClick={() => handleOpenModal('edit')}
            >
              ✏️ 修改提示词
            </button>
          </div>
        </div>
      );
    }

    if (canGenerate) {
      return (
        <div className={styles.placeholder}>
          <button
            className={styles.generateButton}
            onClick={() => onGenerateVideo()}
          >
            生成视频
          </button>
          <button
            className={styles.editPromptLink}
            onClick={() => handleOpenModal('view')}
          >
            📝 查看/编辑提示词
          </button>
          <div className={styles.generateInfo}>
            <p className={styles.infoHint}>错峰模式可节省积分，等待时间约48小时</p>
          </div>
        </div>
      );
    }

    return (
      <div className={styles.placeholder}>
        <p className={styles.placeholderText}>九宫格审核通过后可生成</p>
      </div>
    );
  };

  return (
    <>
      {renderContent()}

      {/* 提示词模态框 */}
      {showModal && (
        <div className={styles.modalOverlay} onClick={() => setShowModal(false)}>
          <div className={styles.modalContent} onClick={e => e.stopPropagation()}>
            <div className={styles.modalHeader}>
              <h3>{activeTab === 'edit' ? '✏️ 修改提示词' : '📝 查看提示词'}</h3>
              <button className={styles.closeButton} onClick={() => setShowModal(false)}>×</button>
            </div>

            {/* Tab 切换 */}
            <div className={styles.tabBar}>
              <button
                className={`${styles.tab} ${activeTab === 'view' ? styles.activeTab : ''}`}
                onClick={() => setActiveTab('view')}
              >
                👁️ 仅查看
              </button>
              <button
                className={`${styles.tab} ${activeTab === 'edit' ? styles.activeTab : ''}`}
                onClick={() => setActiveTab('edit')}
              >
                ✏️ 编辑后生成
              </button>
            </div>

            {/* 提示词内容 */}
            <div className={styles.promptSection}>
              {loadingPrompt ? (
                <div className={styles.loadingPrompt}>加载中...</div>
              ) : (
                <>
                  {activeTab === 'view' ? (
                    <div className={styles.promptView}>
                      <div className={styles.promptHint}>
                        💡 以下是 AI 根据分镜内容自动生成的提示词，用于视频生成
                      </div>
                      <pre className={styles.promptText}>{promptText || '(暂无提示词)'}</pre>
                    </div>
                  ) : (
                    <div className={styles.promptEdit}>
                      <div className={styles.promptHint}>
                        ✏️ 修改提示词可以更好地控制视频生成效果。<strong>修改后的提示词会被保存并用于生成。</strong>
                      </div>
                      <textarea
                        className={styles.promptTextarea}
                        value={promptText}
                        onChange={e => setPromptText(e.target.value)}
                        placeholder="输入自定义提示词..."
                        rows={10}
                      />
                      <div className={styles.promptTips}>
                        <p>💡 <strong>提示：</strong></p>
                        <ul>
                          <li>描述画面主体、动作、场景</li>
                          <li>指定视觉风格（如：水墨风格、写实风格、动漫风格）</li>
                          <li>添加镜头运动描述（如：缓慢推进、横移、环绕）</li>
                          <li>强调关键元素（如：强调角色表情、强调环境氛围）</li>
                        </ul>
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>

            {/* 操作按钮 */}
            <div className={styles.modalActions}>
              <button
                className={styles.cancelButton}
                onClick={() => setShowModal(false)}
              >
                取消
              </button>
              <button
                className={styles.submitButton}
                onClick={handleSubmit}
                disabled={loadingPrompt || (activeTab === 'edit' && !promptText.trim())}
              >
                {activeTab === 'edit' ? '🎬 使用修改后的提示词生成' : '🎬 使用原提示词生成'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};

export default VideoPanel;
