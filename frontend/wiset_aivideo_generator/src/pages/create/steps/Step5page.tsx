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

interface RawStoryboardCharacter {
  char_id?: string;
  name?: string;
  expression?: string;
  pose?: string;
  position?: string;
}

interface RawStoryboardDialogue {
  speaker?: string;
  text?: string;
}

interface RawStoryboardPanel {
  scene?: string;
  shot_size?: string;
  shot_type?: string;
  camera_angle?: string;
  dialogue?: string | RawStoryboardDialogue[];
  effects?: string;
  sfx?: string[];
  characters?: string | RawStoryboardCharacter[];
  background?: {
    scene_desc?: string;
  };
}

interface RawStoryboardData {
  panels?: RawStoryboardPanel[];
}

interface Step5pageProps extends StepContentProps {
  project: Project;
}

const formatCharacters = (characters: RawStoryboardPanel['characters']): string => {
  if (!characters) return '';
  if (typeof characters === 'string') return characters;
  if (!Array.isArray(characters)) return '';

  return characters
    .map((character) => {
      if (!character || typeof character !== 'object') return '';
      const label = character.name || character.char_id || '';
      const expression = character.expression ? ` ${character.expression}` : '';
      return `${label}${expression}`.trim();
    })
    .filter(Boolean)
    .join(' / ');
};

const formatDialogue = (dialogue: RawStoryboardPanel['dialogue']): string => {
  if (!dialogue) return '';
  if (typeof dialogue === 'string') return dialogue;
  if (!Array.isArray(dialogue)) return '';

  return dialogue
    .map((item) => {
      if (!item || typeof item !== 'object') return '';
      const speaker = item.speaker?.trim();
      const text = item.text?.trim();
      if (!speaker && !text) return '';
      return speaker ? `${speaker}: ${text || ''}`.trim() : (text || '');
    })
    .filter(Boolean)
    .join(' / ');
};

const normalizeStoryboardData = (raw: RawStoryboardData): StoryboardData => ({
  panels: Array.isArray(raw?.panels)
    ? raw.panels.map((panel) => ({
        scene: panel.scene || panel.background?.scene_desc || '',
        characters: formatCharacters(panel.characters),
        shot_size: panel.shot_size || panel.shot_type || '',
        camera_angle: panel.camera_angle || '',
        dialogue: formatDialogue(panel.dialogue),
        effects: panel.effects || (Array.isArray(panel.sfx) ? panel.sfx.join(' / ') : ''),
      }))
    : [],
});

const Step5page = ({ project }: Step5pageProps) => {
  const { statusInfo, syncStatus } = useCreateStore();
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
  const loadedEpisodeRef = useRef<string | null>(null);
  const syncedStoryboardEpisodeRef = useRef<string | null>(null);

  const fetchStoryboard = useCallback(async (epId: string) => {
    try {
      const res = await getStoryboard(epId);
      if (res.code !== 200 || !res.data) return false;

      // Check episode status directly, not inferred from storyboardJson being null
      if (res.data.status === 'STORYBOARD_GENERATING') return false;
      if (res.data.status === 'STORYBOARD_FAILED') return false;

      // STORYBOARD_DONE or STORYBOARD_CONFIRMED — parse the data
      if (res.data.storyboardJson) {
        const parsed = normalizeStoryboardData(JSON.parse(res.data.storyboardJson) as RawStoryboardData);
        if (parsed?.panels?.length > 0) {
          setStoryboardData(parsed);
          return true;
        }
      }
    } catch {
      // ignore polling errors and retry on next interval
    }
    return false;
  }, []);

  const stopPolling = useCallback(() => {
    if (pollingRef.current !== null) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
  }, []);

  const startPolling = useCallback((epId: string) => {
    stopPolling();
    loadedEpisodeRef.current = epId;

    fetchStoryboard(epId).then((hasData) => {
      if (hasData || loadedEpisodeRef.current !== epId) return;
      pollingRef.current = setInterval(async () => {
        if (loadedEpisodeRef.current !== epId) {
          stopPolling();
          return;
        }
        const done = await fetchStoryboard(epId);
        if (done) stopPolling();
      }, POLL_INTERVAL);
    });
  }, [fetchStoryboard, stopPolling]);

  useEffect(() => {
    setStoryboardData(null);
    setShowRevision(false);
    syncedStoryboardEpisodeRef.current = null;

    if (reviewEpisodeId) {
      startPolling(reviewEpisodeId);
    } else {
      stopPolling();
    }

    return () => stopPolling();
  }, [reviewEpisodeId, startPolling, stopPolling]);

  useEffect(() => {
    if (!apiError) return;
    const timer = setTimeout(() => setApiError(null), 5000);
    return () => clearTimeout(timer);
  }, [apiError]);

  useEffect(() => {
    if (!isGenerating || !storyboardData || !reviewEpisodeId || !projectId) return;
    if (syncedStoryboardEpisodeRef.current === reviewEpisodeId) return;

    syncedStoryboardEpisodeRef.current = reviewEpisodeId;
    void syncStatus(projectId);
  }, [isGenerating, storyboardData, reviewEpisodeId, projectId, syncStatus]);

  const handleStartStoryboard = useCallback(async () => {
    if (!projectId) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await startStoryboard(projectId);
      if (!isApiSuccess(res)) {
        setApiError(res.message || 'Failed to start storyboard generation');
        return;
      }
      await syncStatus(projectId);
    } catch (e: any) {
      setApiError(e.message || 'Failed to start storyboard generation');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, syncStatus]);

  const handleConfirm = useCallback(async () => {
    if (!reviewEpisodeId) return;
    setIsSubmitting(true);
    setApiError(null);
    stopPolling();
    setStoryboardData(null);
    try {
      const res = await confirmStoryboard(reviewEpisodeId);
      if (isApiSuccess(res)) {
        if (projectId) {
          await syncStatus(projectId);
        }
        return;
      }
      setApiError(res.message || 'Confirm failed');
    } catch (e: any) {
      setApiError(e.message || 'Confirm failed');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId, projectId, stopPolling, syncStatus]);

  const handleRetry = useCallback(async () => {
    setIsSubmitting(true);
    setApiError(null);
    stopPolling();
    setStoryboardData(null);
    try {
      if (!reviewEpisodeId) {
        if (!projectId) {
          setApiError('Missing project id. Please refresh and retry.');
          return;
        }
        const fallbackRes = await startStoryboard(projectId);
        if (!isApiSuccess(fallbackRes)) {
          setApiError(fallbackRes.message || 'Retry failed');
          return;
        }
        await syncStatus(projectId);
        return;
      }

      const res = await retryStoryboard(reviewEpisodeId);
      if (isApiSuccess(res)) {
        if (projectId) {
          await syncStatus(projectId);
        }
        startPolling(reviewEpisodeId);
        return;
      }
      setApiError(res.message || 'Retry failed');
    } catch (e: any) {
      setApiError(e.message || 'Retry failed');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId, projectId, stopPolling, startPolling, syncStatus]);

  const handleRefreshGeneratingState = useCallback(async () => {
    if (!projectId) return;

    setIsSubmitting(true);
    setApiError(null);
    try {
      if (reviewEpisodeId) {
        await fetchStoryboard(reviewEpisodeId);
      }
      await syncStatus(projectId);
    } catch (e: any) {
      setApiError(e.message || 'Failed to refresh status');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId, reviewEpisodeId, fetchStoryboard, syncStatus]);

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
        if (projectId) {
          await syncStatus(projectId);
        }
        startPolling(reviewEpisodeId);
        return;
      }
      setApiError(res.message || 'Revision failed');
    } catch (e: any) {
      setApiError(e.message || 'Revision failed');
    } finally {
      setIsSubmitting(false);
    }
  }, [reviewEpisodeId, feedback, projectId, stopPolling, startPolling, syncStatus]);

  const handleStartProduction = useCallback(async () => {
    if (!projectId) return;
    setIsSubmitting(true);
    setApiError(null);
    try {
      const res = await startProduction(projectId);
      if (!isApiSuccess(res)) {
        setApiError(res.message || 'Failed to start production');
      }
    } catch (e: any) {
      setApiError(e.message || 'Failed to start production');
    } finally {
      setIsSubmitting(false);
    }
  }, [projectId]);

  const progressPercent = totalEpisodes > 0
    ? Math.round((allConfirmed ? 1 : Math.max(0, currentEpisode - 1) / totalEpisodes) * 100)
    : 0;

  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>Storyboard Review</h1>
        <p className={styles.subtitle}>Review and confirm storyboard episode by episode.</p>
      </div>

      {totalEpisodes > 0 && (
        <div className={styles.progressBar}>
          <div className={styles.progressTrack}>
            <div className={styles.progressFill} style={{ width: `${progressPercent}%` }} />
          </div>
          <span className={styles.progressText}>
            {allConfirmed ? totalEpisodes : currentEpisode} / {totalEpisodes} episodes
          </span>
        </div>
      )}

      {isGenerating && (
        <div className={styles.generatingState}>
          <div className={styles.spinner} />
          <p>{statusDescription || 'Generating storyboard...'}</p>
          <button
            className={styles.retryButton}
            onClick={handleRefreshGeneratingState}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Refreshing...' : 'Refresh Status'}
          </button>
        </div>
      )}

      {isFailed && (
        <div className={styles.failedState}>
          <div className={styles.failedIcon}>!</div>
          <p className={styles.failedMessage}>{statusDescription || 'Storyboard generation failed'}</p>
          <button
            className={styles.retryButton}
            onClick={handleRetry}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Processing...' : 'Retry Generation'}
          </button>
        </div>
      )}

      {!isGenerating && !isFailed && !allConfirmed && totalEpisodes > 0 && currentEpisode <= totalEpisodes && (
        <div className={styles.reviewSection}>
          <div className={styles.reviewHeader}>
            <h2 className={styles.episodeTitle}>Episode {currentEpisode}</h2>
            <span className={styles.episodeBadge}>
              {currentEpisode === totalEpisodes ? 'Last Episode' : `${totalEpisodes - currentEpisode} left`}
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
                          {panel.shot_size}
                          {panel.shot_size && panel.camera_angle ? ' / ' : ''}
                          {panel.camera_angle}
                        </span>
                      )}
                    </div>

                    {panel.scene && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>Scene</span>
                        <span className={styles.panelValue}>{panel.scene}</span>
                      </div>
                    )}

                    {panel.characters && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>Characters</span>
                        <span className={styles.panelValue}>{panel.characters}</span>
                      </div>
                    )}

                    {panel.dialogue && (
                      <div className={styles.panelDialogue}>
                        <span className={styles.panelLabel}>Dialogue</span>
                        <p className={styles.dialogueText}>"{panel.dialogue}"</p>
                      </div>
                    )}

                    {panel.effects && (
                      <div className={styles.panelField}>
                        <span className={styles.panelLabel}>Effects</span>
                        <span className={styles.panelEffects}>{panel.effects}</span>
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <p className={styles.storyboardHint}>No storyboard data yet.</p>
            )}
          </div>

          <div className={styles.actionBar}>
            <button
              className={styles.primaryButton}
              onClick={handleConfirm}
              disabled={isSubmitting}
            >
              {isSubmitting
                ? 'Processing...'
                : currentEpisode === totalEpisodes
                  ? 'Confirm and Start Production'
                  : 'Confirm'}
            </button>
            <button
              className={styles.secondaryButton}
              onClick={() => setShowRevision(!showRevision)}
              disabled={isSubmitting}
            >
              {showRevision ? 'Cancel Revision' : 'Revise'}
            </button>
          </div>

          {showRevision && (
            <div className={styles.revisionPanel}>
              <textarea
                className={styles.revisionInput}
                placeholder="Enter revision feedback..."
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
                  {isSubmitting ? 'Submitting...' : 'Submit Revision'}
                </button>
                <button
                  className={styles.secondaryButton}
                  onClick={() => {
                    setFeedback('');
                    setShowRevision(false);
                  }}
                  disabled={isSubmitting}
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {!isGenerating && !isFailed && totalEpisodes === 0 && (
        <div className={styles.startSection}>
          <p className={styles.startHint}>
            Assets are ready. Click below to start generating storyboard episodes.
          </p>
          <button
            className={styles.primaryButton}
            onClick={handleStartStoryboard}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Processing...' : 'Start Storyboard'}
          </button>
        </div>
      )}

      {!isGenerating && !isFailed && allConfirmed && (
        <div className={styles.startSection}>
          <div className={styles.allConfirmed}>
            <div className={styles.allConfirmedIcon}>&#10003;</div>
            <p>All {totalEpisodes} episodes are confirmed.</p>
          </div>
          <button
            className={styles.productionButton}
            onClick={handleStartProduction}
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Processing...' : 'Start Production'}
          </button>
        </div>
      )}

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
