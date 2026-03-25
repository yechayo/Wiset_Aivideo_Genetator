import { useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import styles from './EpisodeCard.module.less';
import type { EpisodeCardData } from '../../../../services/types/episode.types';

interface EpisodeCardProps {
  episode: EpisodeCardData;
  projectId?: string;
  isCurrentReview: boolean;
  isLoadingStoryboard: boolean;
  storyboardStatusDesc: string;
  storyboardFailed: boolean;
  sceneGridUrls: string[];
  onConfirm: () => void;
  onRevise: (feedback: string) => void;
  onRetry: () => void;
  onLoadStoryboard: () => void;
  onGenerateVideo: (panelIndex: number) => void;
  onAutoContinue: () => void;
  isGlobalSubmitting: boolean;
}

type DisplayMode = 'review' | 'production';

const EpisodeCard = ({
  episode,
  projectId,
  isCurrentReview,
  isLoadingStoryboard,
  storyboardStatusDesc,
  storyboardFailed,
  sceneGridUrls,
  onConfirm,
  onRevise,
  onRetry,
  onLoadStoryboard,
  onGenerateVideo,
  onAutoContinue,
  isGlobalSubmitting,
}: EpisodeCardProps) => {
  const navigate = useNavigate();
  const [mode, setMode] = useState<DisplayMode>('review');
  const [reviseText, setReviseText] = useState('');
  const [showReviseInput, setShowReviseInput] = useState(false);

  const handleRevise = useCallback(() => {
    if (reviseText.trim()) {
      onRevise(reviseText.trim());
      setReviseText('');
      setShowReviseInput(false);
    }
  }, [reviseText, onRevise]);

  const canReview = isCurrentReview && episode.status === 'GENERATING';
  const isInProduction = episode.status === 'DONE' || episode.productionStatus === 'IN_PROGRESS';

  // 解析分镜 JSON
  const parsedStoryboard = episode.storyboardJson
    ? (() => {
        try {
          return JSON.parse(episode.storyboardJson);
        } catch {
          return null;
        }
      })()
    : null;

  const panels = parsedStoryboard?.panels || [];

  return (
    <div className={styles.card}>
      {/* Card Header */}
      <div className={styles.header}>
        <div className={styles.episodeInfo}>
          <span className={styles.episodeNum}>EP {episode.episodeNum}</span>
          <h3 className={styles.title}>{episode.title}</h3>
        </div>
        <div className={styles.statusBadges}>
          {episode.status === 'DONE' && (
            <span className={`${styles.badge} ${styles.success}`}>已完成</span>
          )}
          {episode.status === 'GENERATING' && (
            <span className={`${styles.badge} ${styles.processing}`}>生成中</span>
          )}
          {episode.status === 'FAILED' && (
            <span className={`${styles.badge} ${styles.error}`}>失败</span>
          )}
          {episode.productionStatus === 'IN_PROGRESS' && (
            <span className={`${styles.badge} ${styles.active}`}>生产中</span>
          )}
        </div>
      </div>

      {/* Mode Switcher */}
      <div className={styles.modeSwitcher}>
        <button
          className={`${styles.modeBtn} ${mode === 'review' ? styles.active : ''}`}
          onClick={() => setMode('review')}
          disabled={!canReview && !parsedStoryboard}
        >
          分镜审核
        </button>
        <button
          className={`${styles.modeBtn} ${mode === 'production' ? styles.active : ''}`}
          onClick={() => setMode('production')}
          disabled={!isInProduction}
        >
          视频生产
        </button>
      </div>

      {/* Review Mode */}
      {mode === 'review' && (
        <div className={styles.reviewMode}>
          {isLoadingStoryboard && isCurrentReview && (
            <div className={styles.loadingState}>
              <div className={styles.spinner} />
              <p>生成分镜中...</p>
              {storyboardStatusDesc && (
                <span className={styles.statusDesc}>{storyboardStatusDesc}</span>
              )}
            </div>
          )}

          {storyboardFailed && isCurrentReview && (
            <div className={styles.errorState}>
              <p className={styles.errorText}>分镜生成失败</p>
              {storyboardStatusDesc && (
                <span className={styles.errorDesc}>{storyboardStatusDesc}</span>
              )}
              <button
                className={styles.retryBtn}
                onClick={onRetry}
                disabled={isGlobalSubmitting}
              >
                重试
              </button>
            </div>
          )}

          {parsedStoryboard && panels.length > 0 && (
            <div className={styles.storyboardContent}>
              <div className={styles.panelsList}>
                {panels.map((panel: any, idx: number) => (
                  <div key={idx} className={styles.panelItem}>
                    <span className={styles.panelIndex}>#{idx + 1}</span>
                    <div className={styles.panelDetails}>
                      <p><strong>场景:</strong> {panel.scene || panel.background?.scene_desc || '-'}</p>
                      <p><strong>角色:</strong> {
                        typeof panel.characters === 'string'
                          ? panel.characters
                          : Array.isArray(panel.characters)
                            ? panel.characters.map((c: any) => c.name).join(', ')
                            : '-'
                      }</p>
                      <p><strong>对白:</strong> {
                        typeof panel.dialogue === 'string'
                          ? panel.dialogue
                          : Array.isArray(panel.dialogue)
                            ? panel.dialogue.map((d: any) => d.speaker ? `${d.speaker}: ${d.text}` : d.text).join(' ')
                            : '-'
                      }</p>
                    </div>
                  </div>
                ))}
              </div>

              <div className={styles.reviewActions}>
                {!showReviseInput ? (
                  <>
                    <button
                      className={styles.confirmBtn}
                      onClick={onConfirm}
                      disabled={isGlobalSubmitting}
                    >
                      确认分镜
                    </button>
                    <button
                      className={styles.reviseBtn}
                      onClick={() => setShowReviseInput(true)}
                      disabled={isGlobalSubmitting}
                    >
                      修订
                    </button>
                  </>
                ) : (
                  <div className={styles.reviseInput}>
                    <textarea
                      value={reviseText}
                      onChange={(e) => setReviseText(e.target.value)}
                      placeholder="请描述需要修改的内容..."
                      rows={3}
                      className={styles.textarea}
                    />
                    <div className={styles.reviseActions}>
                      <button
                        className={styles.submitBtn}
                        onClick={handleRevise}
                        disabled={!reviseText.trim() || isGlobalSubmitting}
                      >
                        提交修订
                      </button>
                      <button
                        className={styles.cancelBtn}
                        onClick={() => {
                          setShowReviseInput(false);
                          setReviseText('');
                        }}
                      >
                        取消
                      </button>
                    </div>
                  </div>
                )}
              </div>
            </div>
          )}

          {!parsedStoryboard && !isLoadingStoryboard && !storyboardFailed && (
            <div className={styles.emptyState}>
              <p>暂无分镜数据</p>
              {isCurrentReview && (
                <button
                  className={styles.loadBtn}
                  onClick={onLoadStoryboard}
                  disabled={isGlobalSubmitting}
                >
                  加载分镜
                </button>
              )}
            </div>
          )}
        </div>
      )}

      {/* Production Mode */}
      {mode === 'production' && (
        <div className={styles.productionMode}>
          {sceneGridUrls.length > 0 && (
            <div className={styles.sceneGrids}>
              <h4>场景网格图</h4>
              <div className={styles.gridGallery}>
                {sceneGridUrls.map((url, idx) => (
                  <div key={idx} className={styles.gridItem}>
                    <img src={url} alt={`Scene Grid ${idx + 1}`} />
                  </div>
                ))}
              </div>
            </div>
          )}

          {episode.panelStates.length > 0 && (
            <div className={styles.panelStates}>
              <h4>面板状态</h4>
              <div className={styles.panelsGrid}>
                {episode.panelStates.map((panel) => (
                  <div
                    key={panel.panelIndex}
                    className={styles.panelStateCard}
                    onClick={() => {
                      if (projectId) {
                        navigate(`/project/${projectId}/episode/${episode.id}/panel/${panel.panelIndex}`);
                      }
                    }}
                  >
                    <div className={styles.panelHeader}>
                      <span className={styles.panelNum}>#{panel.panelIndex + 1}</span>
                      <span className={`${styles.status} ${styles[panel.videoStatus]}`}>
                        {panel.videoStatus === 'completed' && '已完成'}
                        {panel.videoStatus === 'generating' && '生成中'}
                        {panel.videoStatus === 'pending' && '待生成'}
                        {panel.videoStatus === 'failed' && '失败'}
                      </span>
                    </div>
                    {panel.fusionUrl && (
                      <div className={styles.fusionImage}>
                        <img src={panel.fusionUrl} alt={`Panel ${panel.panelIndex}`} />
                      </div>
                    )}
                    {panel.videoUrl && (
                      <div className={styles.videoPreview}>
                        <video src={panel.videoUrl} controls />
                      </div>
                    )}
                    <button
                      className={styles.enterBtn}
                      onClick={(e) => {
                        e.stopPropagation();
                        if (projectId) {
                          navigate(`/project/${projectId}/episode/${episode.id}/panel/${panel.panelIndex}`);
                        }
                      }}
                    >
                      进入生产
                    </button>
                    {panel.videoStatus === 'failed' && panel.videoTaskId && (
                      <button
                        className={styles.retryBtn}
                        onClick={(e) => {
                          e.stopPropagation();
                          onGenerateVideo(panel.panelIndex);
                        }}
                        disabled={isGlobalSubmitting}
                      >
                        重试
                      </button>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {sceneGridUrls.length === 0 && episode.panelStates.length === 0 && (
            <div className={styles.emptyState}>
              <p>暂无生产数据</p>
            </div>
          )}

          {episode.panelStates.length > 0 && (
            <div className={styles.productionActions}>
              <button
                className={styles.autoContinueBtn}
                onClick={onAutoContinue}
                disabled={isGlobalSubmitting}
              >
                一键自动化
              </button>
            </div>
          )}
        </div>
      )}

      {/* Final Video */}
      {episode.finalVideoUrl && (
        <div className={styles.finalVideo}>
          <h4>最终视频</h4>
          <video src={episode.finalVideoUrl} controls />
        </div>
      )}

      {/* Error Message */}
      {episode.errorMsg && (
        <div className={styles.errorMessage}>
          <span className={styles.errorIcon}>⚠</span>
          {episode.errorMsg}
        </div>
      )}
    </div>
  );
};

export default EpisodeCard;
