/**
 * Step5Compose - 逐集合成视频与下载
 * 每集独立合成，支持按集/按章/全部批量操作
 * 只有所有 panel 视频完成的剧集才可合成
 */
import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import styles from './Step5Compose.module.less';
import type { EpisodeState } from './types';
import {
  getEpisodes,
  getPanels,
  composeEpisode,
} from '../../../services/episodeService';
import { mergeVideos } from '../../../services/projectService';
import { useSseProgress } from './hooks/useSseProgress';

interface ChapterState {
  chapterIndex: number;
  title: string;
  episodes: EpisodeState[];
}

interface Step5ComposeProps {
  project: any;
}

const ChevronIcon = ({ open }: { open: boolean }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    {open ? <polyline points="6 9 12 15 18 9" /> : <polyline points="9 18 15 12 9 6" />}
  </svg>
);

const BookIcon = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
    <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
  </svg>
);

const SpinIcon = () => <span className={styles.btnSpinner} />;

const CheckIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="20 6 9 17 4 12" />
  </svg>
);

/**
 * 检查某集是否所有 panel 视频都已完成（可合成）
 */
function checkAllPanelsReady(panels: any[], isComicCommentary: boolean): boolean {
  if (panels.length === 0) return false;
  return panels.every(p => {
    const info = p.panelInfo || p;
    if (isComicCommentary) {
      return info.mergeStatus === 'completed' && info.videoWithNarrationUrl;
    }
    return info.videoStatus === 'completed' && info.videoUrl;
  });
}

const Step5Compose: React.FC<Step5ComposeProps> = ({ project }) => {
  const projectId = project?.projectId;
  const isComicCommentary = project?.projectInfo?.productionMode === 'comic_commentary';

  const [chapters, setChapters] = useState<ChapterState[]>([]);
  const [loading, setLoading] = useState(true);
  const [composingEpisodeIds, setComposingEpisodeIds] = useState<Set<number>>(new Set());
  const [merging, setMerging] = useState(false);
  const [collapsedChapters, setCollapsedChapters] = useState<Set<number>>(new Set());
  const [expandedEpisodeId, setExpandedEpisodeId] = useState<number | null>(null);
  // episodeId → boolean: 是否所有 panel 视频已完成
  const [episodeReadyMap, setEpisodeReadyMap] = useState<Record<number, boolean>>({});
  // 项目合并后的完整视频 URL
  const [finalVideoUrl, setFinalVideoUrl] = useState<string | null>(project?.projectInfo?.finalVideoUrl || null);
  // 防止同一 episode 并发重复刷新
  const refreshInFlightRef = useRef<Set<number>>(new Set());

  /**
   * 检查并更新单个 episode 的就绪状态
   */
  const refreshEpisodeReady = useCallback(async (episodeId: number) => {
    if (!projectId || refreshInFlightRef.current.has(episodeId)) return;
    refreshInFlightRef.current.add(episodeId);
    try {
      const panelRes = await getPanels(projectId, episodeId);
      const panels = panelRes.data || [];
      const ready = checkAllPanelsReady(panels, isComicCommentary);
      setEpisodeReadyMap(prev => ({ ...prev, [episodeId]: ready }));
    } catch {
      // 忽略错误，保持原状态
    } finally {
      refreshInFlightRef.current.delete(episodeId);
    }
  }, [projectId, isComicCommentary]);

  // 监听视频生成完成事件，实时刷新对应 episode 的就绪状态
  useSseProgress(projectId, {
    onPanelVideoDone: (data) => {
      if (data.episodeId) refreshEpisodeReady(data.episodeId);
    },
    onPanelVideoFailed: (data) => {
      if (data.episodeId) refreshEpisodeReady(data.episodeId);
    },
    onPanelMergeDone: (data) => {
      if (data.episodeId) refreshEpisodeReady(data.episodeId);
    },
    onPanelMergeFailed: (data) => {
      if (data.episodeId) refreshEpisodeReady(data.episodeId);
    },
  });

  // Load episodes + check panel readiness
  const loadData = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    try {
      const res = await getEpisodes(projectId, { size: 999 });
      const items = res.data?.items || [];

      // Group by chapter
      const chapterMap = new Map<string, EpisodeState[]>();
      for (const item of items) {
        const ep = item.episodeInfo || {};
        const epNum = ep.episodeNum || 0;
        const epState: EpisodeState = {
          episodeId: item.id,
          episodeIndex: epNum,
          title: ep.title || `第${epNum}集`,
          sceneSummaryMap: ep.sceneSummaryMap || {},
          segments: [],
          gridStatus: ep.gridStatus,
          gridImages: ep.gridImages,
          splitShots: ep.splitShots,
          gridRejectionFeedback: ep.gridRejectionFeedback,
          panelApproved: ep.panelApproved,
          scriptStatus: ep.scriptStatus,
          storyboardStatus: ep.storyboardStatus,
          episodeInfo: ep,
          composedVideoUrl: ep.composedVideoUrl || null,
          composedVideoStatus: ep.composedVideoStatus || '',
        };
        const chapterTitle = (ep.chapterTitle || '').replace(/^#+\s*/, '').trim() || '未分章';
        if (!chapterMap.has(chapterTitle)) chapterMap.set(chapterTitle, []);
        chapterMap.get(chapterTitle)!.push(epState);
      }
      const chs: ChapterState[] = Array.from(chapterMap.entries()).map(([title, eps], idx) => ({
        chapterIndex: idx + 1,
        title,
        episodes: eps,
      }));
      setChapters(chs);

      // Load panels for each episode to check readiness
      const allEps = chs.flatMap(ch => ch.episodes);
      // 先全部设为 false，完成后按需更新
      const readyMap: Record<number, boolean> = {};
      allEps.forEach(ep => { readyMap[ep.episodeId] = false; });
      setEpisodeReadyMap(readyMap);
      // 并发刷新每个 episode 的就绪状态
      await Promise.all(allEps.map(ep => refreshEpisodeReady(ep.episodeId)));
    } catch {
      console.error('加载数据失败');
    } finally {
      setLoading(false);
    }
  }, [projectId, isComicCommentary, refreshEpisodeReady]);

  useEffect(() => { loadData(); }, [loadData]);

  // 页面从隐藏恢复时，确保数据是最新的（防止从 Step4 切回时遗漏 SSE 事件）
  // 使用 debounce 避免快速切换标签页时触发多次 loadData
  const visibilityTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        if (visibilityTimerRef.current) clearTimeout(visibilityTimerRef.current);
        visibilityTimerRef.current = setTimeout(() => loadData(), 300);
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      if (visibilityTimerRef.current) clearTimeout(visibilityTimerRef.current);
    };
  }, [loadData]);

  // 同步 project prop 中的 finalVideoUrl（其他端合成或 SSE 推送后可能更新）
  useEffect(() => {
    if (project?.projectInfo?.finalVideoUrl) {
      setFinalVideoUrl(project.projectInfo.finalVideoUrl);
    }
  }, [project?.projectInfo?.finalVideoUrl]);

  // All episodes
  const allEpisodes = useMemo(() => chapters.flatMap(ch => ch.episodes), [chapters]);
  const composedCount = allEpisodes.filter(ep => ep.composedVideoUrl).length;

  // Check if an episode is ready for composition (all panels have video)
  const canCompose = useCallback((ep: EpisodeState): boolean => {
    // Already composed
    if (ep.composedVideoUrl) return true;
    return !!episodeReadyMap[ep.episodeId];
  }, [episodeReadyMap]);

  // Count of ready-but-not-yet-composed episodes
  const readyCount = allEpisodes.filter(ep => canCompose(ep) && !ep.composedVideoUrl).length;

  // Single episode compose
  const handleComposeEpisode = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    setComposingEpisodeIds(prev => new Set(prev).add(episodeId));
    try {
      await composeEpisode(projectId, episodeId);
      await loadData();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '合成失败');
    } finally {
      setComposingEpisodeIds(prev => { const next = new Set(prev); next.delete(episodeId); return next; });
    }
  }, [projectId, loadData]);

  // Chapter batch compose
  const handleChapterBatchCompose = useCallback((chapterIndex: number) => {
    const chapter = chapters.find(ch => ch.chapterIndex === chapterIndex);
    if (!chapter) return;
    chapter.episodes.forEach(ep => {
      if (!ep.composedVideoUrl && canCompose(ep)) {
        handleComposeEpisode(ep.episodeId);
      }
    });
  }, [chapters, handleComposeEpisode, canCompose]);

  // Project batch compose
  const handleProjectBatchCompose = useCallback(() => {
    allEpisodes.forEach(ep => {
      if (!ep.composedVideoUrl && canCompose(ep)) {
        handleComposeEpisode(ep.episodeId);
      }
    });
  }, [allEpisodes, handleComposeEpisode, canCompose]);

  // Project-level merge (merge all episode videos into one)
  const handleProjectMerge = useCallback(async () => {
    if (!projectId || merging) return;
    setMerging(true);
    try {
      const res = await mergeVideos(projectId);
      const url = res?.data?.finalVideoUrl;
      if (url) setFinalVideoUrl(url);
      await loadData();
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '合并失败');
    } finally {
      setMerging(false);
    }
  }, [projectId, merging, loadData]);

  const toggleChapter = useCallback((chIdx: number) => {
    setCollapsedChapters(prev => {
      const next = new Set(prev);
      if (next.has(chIdx)) next.delete(chIdx); else next.add(chIdx);
      return next;
    });
  }, []);

  const toggleEpisode = useCallback((epId: number) => {
    setExpandedEpisodeId(prev => prev === epId ? null : epId);
  }, []);

  if (loading) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.loadingState}><div className={styles.spinner} /><p>加载中...</p></div>
      </div>
    );
  }

  const hasComposing = composingEpisodeIds.size > 0;

  return (
    <div className={styles.pageContainer}>
      {/* Header */}
      <div className={styles.pageHeader}>
        <div className={styles.titleSection}>
          <h1 className={styles.pageTitle}>视频合成与下载</h1>
        </div>
      </div>

      {/* Stats Bar */}
      <div className={styles.statsBar}>
        <div className={styles.statsInfo}>
          <span className={styles.completedCount}>{composedCount}</span>
          <span className={styles.separator}>/</span>
          <span className={styles.totalCount}>{allEpisodes.length}</span>
          <span className={styles.statsLabel}>集已合成</span>
          {readyCount > 0 && (
            <span className={styles.readyHint}>
              （{readyCount} 集可合成）
            </span>
          )}
        </div>
        <div className={styles.batchBarActions}>
          <button
            className={styles.btnSuccess}
            onClick={handleProjectBatchCompose}
            disabled={hasComposing || readyCount === 0}
          >
            {hasComposing ? <><SpinIcon /> 合成中...</> : '全部合成'}
          </button>
        </div>
      </div>

      {/* Episode list */}
      <div className={styles.tabContent}>
        {chapters.length === 0 ? (
          <div className={styles.emptyState}><p>暂无章节数据</p></div>
        ) : (
          chapters.map(chapter => {
            const chapterOpen = !collapsedChapters.has(chapter.chapterIndex);
            const chComposedCount = chapter.episodes.filter(ep => ep.composedVideoUrl).length;
            const chReadyCount = chapter.episodes.filter(ep => canCompose(ep) && !ep.composedVideoUrl).length;
            const chAllDone = chComposedCount === chapter.episodes.length;
            return (
              <div key={chapter.chapterIndex} className={styles.chapterGroup}>
                <div className={styles.chapterHeader}>
                  <button className={styles.chapterHeaderLeft} onClick={() => toggleChapter(chapter.chapterIndex)}>
                    <ChevronIcon open={chapterOpen} />
                    <BookIcon />
                    <h2 className={styles.chapterTitle}>{chapter.title}</h2>
                    <span className={styles.chapterProgress}>
                      <span className={styles.chapterProgressText}>{chComposedCount}/{chapter.episodes.length}</span>
                    </span>
                  </button>
                  <div className={styles.chapterBatchActions}>
                    <button
                      className={styles.btnGhost}
                      onClick={() => handleChapterBatchCompose(chapter.chapterIndex)}
                      disabled={hasComposing || chReadyCount === 0}
                    >
                      {chAllDone ? '已完成' : chReadyCount > 0 ? `批量合成 (${chReadyCount})` : '暂无可合成'}
                    </button>
                  </div>
                </div>
                {chapterOpen && (
                <div className={styles.episodeList}>
                  {chapter.episodes.map(ep => {
                    const isComposing = composingEpisodeIds.has(ep.episodeId);
                    const isDone = !!ep.composedVideoUrl;
                    const isReady = canCompose(ep);
                    let statusLabel: string;
                    let statusClass: string;
                    if (isComposing) {
                      statusLabel = '合成中';
                      statusClass = 'generating';
                    } else if (isDone) {
                      statusLabel = '已合成';
                      statusClass = 'completed';
                    } else if (isReady) {
                      statusLabel = '可合成';
                      statusClass = 'ready';
                    } else {
                      statusLabel = '视频未完成';
                      statusClass = 'pending';
                    }
                    return (
                      <div key={ep.episodeId} className={styles.episodeCard}>
                        <div className={styles.episodeHeader}>
                          <button className={styles.episodeHeaderLeft} onClick={() => toggleEpisode(ep.episodeId)}>
                            <h3 className={styles.episodeTitle}>
                              第{ep.episodeIndex}集 {ep.title}
                            </h3>
                            <span className={`${styles.statusBadge} ${styles[`status${statusClass.charAt(0).toUpperCase()}${statusClass.slice(1)}`]}`}>
                              <span className={styles.statusDot} />
                              {statusLabel}
                            </span>
                          </button>
                          <div className={styles.episodeActions}>
                            {isDone ? (
                              <>
                                <button
                                  className={styles.btnGhost}
                                  onClick={() => handleComposeEpisode(ep.episodeId)}
                                  disabled={isComposing}
                                >
                                  {isComposing ? <><SpinIcon /> 合成中...</> : '重新合成'}
                                </button>
                                <a
                                  className={styles.btnSuccess}
                                  href={ep.composedVideoUrl || undefined}
                                  download
                                  target="_blank"
                                  rel="noreferrer"
                                >
                                  <CheckIcon /> 下载
                                </a>
                              </>
                            ) : (
                              <button
                                className={styles.btnSuccess}
                                onClick={() => handleComposeEpisode(ep.episodeId)}
                                disabled={isComposing || !isReady}
                              >
                                {isComposing ? <><SpinIcon /> 合成中...</> : '合成'}
                              </button>
                            )}
                          </div>
                        </div>

                        {/* Expand video player */}
                        {expandedEpisodeId === ep.episodeId && isDone && ep.composedVideoUrl && (
                          <div className={styles.videoWrap}>
                            <video
                              className={styles.videoPlayer}
                              controls
                              src={ep.composedVideoUrl}
                              preload="metadata"
                            />
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
                )}
              </div>
            );
          })
        )}

        {/* Project-level merge */}
        {composedCount === allEpisodes.length && allEpisodes.length > 0 && (
          <div className={styles.projectMergeSection}>
            <div className={styles.projectMergeInfo}>
              <span>所有集已合成完毕</span>
            </div>
            <button
              className={styles.btnPrimary}
              onClick={handleProjectMerge}
              disabled={merging}
            >
              {merging ? <><SpinIcon /> 合并中...</> : '合并为完整视频'}
            </button>
          </div>
        )}

        {/* 完整视频播放器 */}
        {finalVideoUrl && (
          <div className={styles.projectMergeSection}>
            <div className={styles.projectMergeInfo}>
              <span>完整视频</span>
            </div>
            <div className={styles.videoWrap}>
              <video
                className={styles.videoPlayer}
                controls
                src={finalVideoUrl}
                preload="metadata"
              />
            </div>
            <a
              className={styles.btnSuccess}
              href={finalVideoUrl}
              download
              target="_blank"
              rel="noreferrer"
              style={{ marginTop: 12, display: 'inline-flex' }}
            >
              <CheckIcon /> 下载完整视频
            </a>
          </div>
        )}
      </div>
    </div>
  );
};

export default Step5Compose;
