import { useEffect, useState, useCallback, useRef } from 'react';
import styles from './Step5page.module.less';
import type { StepContentProps } from '../types';
import type { Project, StoryboardData } from '../../../services';
import { useCreateStore } from '../../../stores/createStore';
import {
  getStoryboard,
  startStoryboard,
  confirmStoryboard,
  reviseStoryboard,
  retryStoryboard,
  startProduction,
} from '../../../services/projectService';
import { isApiSuccess } from '../../../services/apiClient';

const POLL_INTERVAL = 3000;

interface Step5pageProps extends StepContentProps {
  project: Project;
}

const Step5page = ({ project }: Step5pageProps) => {
  const { statusInfo } = useCreateStore();
  const projectId = project.projectId;

  const currentEpisode = statusInfo?.storyboardCurrentEpisode ?? 0;
  const totalEpisodes = statusInfo?.storyboardTotalEpisodes ?? 0;
  const reviewEpisodeId = statusInfo?.storyboardReviewEpisodeId ?? null;
  const allConfirmed = statusInfo?.storyboardAllConfirmed ?? false;
  const isGenerating = statusInfo?.isGenerating ?? false;
  const isFailed = statusInfo?.isFailed ?? false;
  const statusDescription = statusInfo?.statusDescription ?? '';

  const [feedback, setFeedback] = useState('');
  const [showRevision, setShowRevision] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [apiError, setApiError] = useState<string | null>(null);
  const [storyboardData, setStoryboardData] = useState<StoryboardData | null>(null);

  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const loadedEpisodeRef = useRef<number | null>(null);

  // 单次拉取分镜数据
  const fetchStoryboard = useCallback(async (epId: number) => {
    try {
      const res = await getStoryboard(epId);
      if (res.code === 200 && res.data?.storyboardJson) {
        const parsed = JSON.parse(res.data.storyboardJson) as StoryboardData;
        if (parsed?.panels?.length > 0) {
          setStoryboardData(parsed);
          return true;
        }
      }
    } catch {
      // 轮询中忽略错误，下次继续
    }
    return false;
  }, []);

  // 启动轮询：每 3s 拉取分镜，直到数据出现
  const startPolling = useCallback((epId: number) => {
    stopPolling();
    loadedEpisodeRef.current = epId;

    // 立即拉一次
    fetchStoryboard(epId).then((hasData) => {
      if (hasData || loadedEpisodeRef.current !== epId) return;
      pollingRef.current = setInterval(async () => {
        if (loadedEpisodeRef.current !== epId) {
          stopPolling();
          return;
        }
        const hasData = await fetchStoryboard(epId);
        if (hasData) stopPolling();
      }, POLL_INTERVAL);
    });
  }, [fetchStoryboard]);

  // 停止轮询
  const stopPolling = useCallback(() => {
    if (pollingRef.current !== null) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  // reviewEpisodeId 变化时：清空旧数据，启动轮询新集
  useEffect(() => {
    setStoryboardData(null);
    setShowRevision(false);
    if (reviewEpisodeId) {
      startPolling(reviewEpisodeId);
    } else {
      stopPolling();
    }
    return () => stopPolling();
  }, [reviewEpisodeId, startPolling, stopPolling]);

  useEffect(() => {
    if (apiError) {
      const timer = setTimeout(() => setApiError(null), 5000);
      return () => clearTimeout(timer);
    }
  }, [apiError]);

  const handleStartStoryboard = useCallback(async () => {
    if (!projectId) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await startStoryboard(projectId);
      if (isApiSuccess(res)) return;
      setApiError(res.message || '启动失败');
    } catch (e: any) {
      setApiError(e.message || '启动分镜生成失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId]);

  const handleConfirm = useCallback(async () => {
    if (!reviewEpisodeId) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await confirmStoryboard(reviewEpisodeId);
      if (isApiSuccess(res)) {
        setStoryboardData(null);
        return;
      }
      setApiError(res.message || '确认失败');
    } catch (e: any) {
      setApiError(e.message || '确认失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId]);

  const handleRetry = useCallback(async () => {
    if (!reviewEpisodeId) return;
    setIsSubmitting(true);
    setApiError(null);
    stopPolling();
    setStoryboardData(null);
    try {
      const res = await retryStoryboard(reviewEpisodeId);
      if (isApiSuccess(res)) {
        startPolling(reviewEpisodeId);
        return;
      }
      setApiError(res.message || '重试失败');
    } catch (e: any) {
      setApiError(e.message || '重试失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId, stopPolling, startPolling]);

  const handleRevise = useCallback(async () => {
    if (!reviewEpisodeId || !feedback.trim()) return;
    setIsSubmitting(true);
    setApiError(null);
    stopPolling();
    setStoryboardData(null);
    try {
      const res = await reviseStoryboard(reviewEpisodeId, feedback.trim());
      if (isApiSuccess(res)) {
        setFeedback('');
        setShowRevision(false);
        startPolling(reviewEpisodeId);
        return;
      }
      setApiError(res.message || '修改失败');
    } catch (e: any) {
      setApiError(e.message || '修改失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId, feedback, stopPolling, startPolling]);

  const handleStartProduction = useCallback(async () => {
    if (!projectId) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await startProduction(projectId);
      if (isApiSuccess(res)) return;
      setApiError(res.message || '启动生产失败');
    } catch (e: any) {
      setApiError(e.message || '启动生产失败');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId]);

  const progressPercent = totalEpisodes > 0 ? Math.round((allConfirmed ? 1 : (currentEpisode - 1) / totalEpisodes) * 100) : 0;

  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>分镜审核</h1>
        <p className={styles.subtitle}>逐集审核并确认分镜脚本</p>
      </div>

      {/* 进度条 */}
      {totalEpisodes > 0 && (
        <div className={styles.progressBar}>
          <div className={styles.progressTrack}>
            <div
              className={styles.progressFill}
              style={{ width: `${progressPercent}%` }}
            />
          </div>
          <span className={styles.progressText}>
            {allConfirmed ? totalEpisodes : currentEpisode} / {totalEpisodes} 集
          </span>
        </div>
      )}

      {/* 生成中 */}
      {isGenerating && (
        <div className={styles.generatingState}>
          <div className={styles.spinner} />
          <p>{statusDescription || '正在生成分镜...'}</p>
        </div>
      )}

      {/* 失败状态 */}
      {isFailed && (
        <div className={styles.failedState}>
          <div className={styles.failedIcon}>!</div>
          <p className={styles.failedMessage}>{statusDescription || '分镜生成失败'}</p>
          <button
            className={styles.retryButton}
            onClick={handleRetry}
            disabled={isSubmitting}
          >
            {isSubmitting ? '处理中...' : '重试生成'}
          </button>
        </div>
      )}

      {/* 审核状态 */}
      {!isGenerating && !isFailed && !allConfirmed && totalEpisodes > 0 && currentEpisode <= totalEpisodes && (
        <div className={styles.reviewSection}>
          <div className={styles.reviewHeader}>
            <h2 className={styles.episodeTitle}>第 {currentEpisode} 集分镜</h2>
            <span className={styles.episodeBadge}>
              {currentEpisode === totalEpisodes ? '最后一集' : `剩余 ${totalEpisodes - currentEpisode} 集`}
            </span>
          </div>

          <div className={styles.storyboardContent}>
            {storyboardData && storyboardData.panels.length > 0 ? (
              <div className={styles.panelList}>
                {storyboardData.panels.map((panel, index) => (
                  <div key={index} className={styles.panelCard}>
                    <div className={styles.panelHeader}>
                      <span className={styles.panelIndex}>#{index + 1}</span>
                      {(panel.shot_size || panel.camera_angle) && (
                        <span className={styles.panelMeta}>
                          {panel.shot_size && panel.shot_size}
                          {panel.shot_size && panel.camera_angle && ' / '}
                          {panel.camera_angle && panel.camera_angle}
                        </span>
                      )}
                    </div>
                    {panel.scene && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>场景</span>
                        <span className={styles.panelValue}>{panel.scene}</span>
                      </div>
                    )}
                    {panel.characters && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>角色</span>
                        <span className={styles.panelValue}>{panel.characters}</span>
                      </div>
                    )}
                    {panel.dialogue && (
                      <div className={styles.panelDialogue}>
                        <span className={styles.panelLabel}>台词</span>
                        <p className={styles.dialogueText}>"{panel.dialogue}"</p>
                      </div>
                    )}
                    {panel.effects && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>特效</span>
                        <span className={styles.panelEffects}>{panel.effects}</span>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p className={styles.storyboardHint}>暂无分镜数据</p>
            )}
          </div>

          <div className={styles.actionBar}>
            <button
              className={styles.primaryButton}
              onClick={handleConfirm}
              disabled={isSubmitting}
            >
              {isSubmitting ? '处理中...' : currentEpisode === totalEpisodes ? '确认并开始生产' : '确认'}
            </button>
            <button
              className={styles.secondaryButton}
              onClick={() => setShowRevision(!showRevision)}
              disabled={isSubmitting}
            >
              {showRevision ? '取消修改' : '修改'}
            </button>
          </div>

          {showRevision && (
            <div className={styles.revisionPanel}>
              <textarea
                className={styles.revisionInput}
                placeholder="请输入修改意见，例如：第3个分镜的景别太近，改为远景..."
                value={feedback}
                onChange={(e) => setFeedback(e.target.value)}
                rows={3}
                disabled={isSubmitting}
              />
              <div className={styles.revisionActions}>
                <button
                  className={styles.primaryButton}
                  onClick={handleRevise}
                  disabled={isSubmitting || !feedback.trim()}
                >
                  {isSubmitting ? '提交中...' : '提交修改'}
                </button>
                <button
                  className={styles.secondaryButton}
                  onClick={() => { setFeedback(''); setShowRevision(false); }}
                  disabled={isSubmitting}
                >
                  取消
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* 未启动 */}
      {!isGenerating && !isFailed && totalEpisodes === 0 && (
        <div className={styles.startSection}>
          <p className={styles.startHint}>素材已准备就绪，点击下方按钮开始逐集生成分镜脚本。</p>
          <button
            className={styles.primaryButton}
            onClick={handleStartStoryboard}
            disabled={isSubmitting}
          >
            {isSubmitting ? '处理中...' : '开始生成分镜'}
          </button>
        </div>
      )}

      {/* 全部完成 */}
      {!isGenerating && !isFailed && allConfirmed && (
        <div className={styles.startSection}>
          <div className={styles.allConfirmed}>
            <div className={styles.allConfirmedIcon}>&#10003;</div>
            <p>所有 {totalEpisodes} 集分镜已审核完成</p>
          </div>
          <button
            className={styles.productionButton}
            onClick={handleStartProduction}
            disabled={isSubmitting}
          >
            {isSubmitting ? '处理中...' : '开始视频生产'}
          </button>
        </div>
      )}

      {/* 错误提示 */}
      {apiError && (
        <div className={styles.apiError}>
          <span>{apiError}</span>
          <button className={styles.errorDismiss} onClick={() => setApiError(null)}>x</button>
        </div>
      )}
    </div>
  );
};

export default Step5page;
