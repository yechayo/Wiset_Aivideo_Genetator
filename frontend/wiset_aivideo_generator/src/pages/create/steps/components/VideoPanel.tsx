import React from 'react';
import styles from './VideoPanel.module.less';
import type { SegmentPipelineStep } from '../types';

export interface VideoPanelProps {
  videoUrl: string | null;
  pipelineStep: SegmentPipelineStep;
  onGenerateVideo: () => void;
}

// Loading spinner component
const LoadingSpinner: React.FC = () => (
  <div className={styles.spinner}>
    <div className={styles.spinnerRing}></div>
  </div>
);

const VideoPanel: React.FC<VideoPanelProps> = ({ videoUrl, pipelineStep, onGenerateVideo }) => {
  const canGenerate = pipelineStep === 'comic_approved';
  const isGenerating = pipelineStep === 'video_generating';
  const isCompleted = pipelineStep === 'video_completed' && videoUrl;
  const isFailed = pipelineStep === 'video_failed';

  const renderContent = () => {
    if (isCompleted && videoUrl) {
      return (
        <video
          className={styles.videoPlayer}
          controls
          src={videoUrl}
        >
          您的浏览器不支持视频播放。
        </video>
      );
    }

    if (isGenerating) {
      return (
        <div className={styles.placeholder}>
          <LoadingSpinner />
          <p className={styles.placeholderText}>视频生成中...</p>
        </div>
      );
    }

    if (isFailed) {
      return (
        <div className={styles.placeholder}>
          <p className={styles.errorText}>视频生成失败</p>
          <button
            className={styles.retryButton}
            onClick={onGenerateVideo}
          >
            ⟳ 重试
          </button>
        </div>
      );
    }

    if (canGenerate) {
      return (
        <div className={styles.placeholder}>
          <button
            className={styles.generateButton}
            onClick={onGenerateVideo}
          >
            生成视频
          </button>
        </div>
      );
    }

    return (
      <div className={styles.placeholder}>
        <p className={styles.placeholderText}>四宫格审核通过后可生成</p>
      </div>
    );
  };

  return (
    <div className={styles.videoPanel}>
      <div className={styles.videoContainer}>
        {renderContent()}
      </div>
    </div>
  );
};

export default VideoPanel;
