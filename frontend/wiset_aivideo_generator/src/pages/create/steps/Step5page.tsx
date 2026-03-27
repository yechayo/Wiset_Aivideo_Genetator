import { useState, useCallback, useEffect, useRef } from 'react';
import styles from './Step5page.module.less';
import type {
  ChapterState,
  EpisodeState,
  SegmentState,
  ExpansionState,
} from './types';
import { getEpisodes, getPanels, generatePanels, getPanelGenerateStatus, generateBackground, revisePanel } from '../../../services/episodeService';
import { getCharacterStatus } from '../../../services/characterService';
import EpisodeCard from './components/EpisodeCard';

interface Step5pageProps {
  project: any;
}

/**
 * Step5page: 视频生产工作台
 *
 * 展示三级可展开列表（章节 → 集数 → 片段），
 * 顶部显示完成进度统计栏。
 */
const Step5page = ({ project }: Step5pageProps) => {
  const projectId = project?.projectId;

  // 数据状态
  const [chapters, setChapters] = useState<ChapterState[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [generatingEpisodeId, setGeneratingEpisodeId] = useState<number | null>(null);
  const [charAvatarMap, setCharAvatarMap] = useState<Record<string, string>>({});
  const [generatingBackgroundPanelId, setGeneratingBackgroundPanelId] = useState<string | null>(null);
  const [revisingEpisodeId, setRevisingEpisodeId] = useState<number | null>(null);

  // UI 状态：当前展开的集数/片段（手风琴模式）
  const [expansion, setExpansion] = useState<ExpansionState>({
    expandedEpisodeId: null,
    expandedSegmentKey: null,
  });

  /**
   * 加载剧集列表
   */
  const loadEpisodes = useCallback(async () => {
    if (!projectId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await getEpisodes(projectId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) {
        throw new Error(res.message || '加载失败');
      }
      const items = res.data.items || [];

      // 按章节分组
      const chapterMap = new Map<string, any[]>();
      items.forEach(ep => {
        const chapterTitle = ep.episodeInfo?.chapterTitle?.trim() || '未分章';
        if (!chapterMap.has(chapterTitle)) {
          chapterMap.set(chapterTitle, []);
        }
        chapterMap.get(chapterTitle)!.push(ep);
      });

      const builtChapters: ChapterState[] = [];
      let chapterIndex = 0;
      for (const [chapterTitle, chapterEpisodes] of chapterMap.entries()) {
        chapterIndex++;
        const episodeStates: EpisodeState[] = chapterEpisodes.map((ep, idx) => ({
          episodeId: ep.id,
          episodeIndex: ep.episodeInfo?.episodeNum || idx + 1,
          title: ep.episodeInfo?.title,
          segments: [],
        }));
        builtChapters.push({
          chapterIndex,
          title: chapterTitle,
          episodes: episodeStates,
        });
      }
      setChapters(builtChapters);
    } catch (err: any) {
      setError(err?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    loadEpisodes();
  }, [loadEpisodes]);

  // 计算完成统计
  const totalSegments = chapters.reduce(
    (sum, ch) => sum + ch.episodes.reduce((s, ep) => s + ep.segments.length, 0),
    0
  );
  const completedSegments = chapters.reduce(
    (sum, ch) =>
      sum +
      ch.episodes.reduce(
        (s, ep) => s + ep.segments.filter(seg => seg.pipelineStep === 'video_completed').length,
        0
      ),
    0
  );

  // 记录已加载过分镜的集数，避免重复请求
  const panelsLoadedRef = useRef<Set<number>>(new Set());

  /**
   * 加载分镜中涉及的角色三视图
   */
  const loadCharacterAvatars = useCallback(async (charIds: string[]) => {
    const missing = charIds.filter(id => id && !charAvatarMap[id]);
    if (missing.length === 0 || !projectId) return;
    try {
      const results = await Promise.allSettled(
        missing.map(charId => getCharacterStatus(projectId, charId))
      );
      const newMap: Record<string, string> = {};
      results.forEach((r, i) => {
        if (r.status === 'fulfilled' && r.value?.data) {
          const d = r.value.data;
          if (d.threeViewGridUrl) {
            newMap[missing[i]] = d.threeViewGridUrl;
          } else if (d.expressionGridUrl) {
            newMap[missing[i]] = d.expressionGridUrl;
          }
        }
      });
      if (Object.keys(newMap).length > 0) {
        setCharAvatarMap(prev => ({ ...prev, ...newMap }));
      }
    } catch {
      // 静默失败
    }
  }, [projectId, charAvatarMap]);

  /**
   * 加载某集的分镜列表
   */
  const loadPanelsForEpisode = useCallback(async (episodeId: number) => {
    if (!projectId || panelsLoadedRef.current.has(episodeId)) return;
    panelsLoadedRef.current.add(episodeId);
    let charIds: string[] = [];
    try {
      const res = await getPanels(projectId, episodeId);
      if ((res.code !== 0 && res.code !== 200) || !res.data) return;
      const panels = res.data || [];

      // 收集涉及的角色 ID
      charIds = [...new Set(
        panels.flatMap((p: any) => (p.panelInfo?.characters || []).map((c: any) => c.char_id).filter(Boolean))
      )];

      // 将分镜转为 segments
      const segments: SegmentState[] = panels.map((panel: any, idx: number) => {
        const info = panel.panelInfo || {};
        // 解析角色头像列表
        const characterAvatars = (info.characters || []).map((c: any) => ({
          name: c.char_id || '',
          avatarUrl: charAvatarMap[c.char_id] || '',
        }));
        // 拼接台词
        const dialogue = (info.dialogue || [])
          .map((d: any) => d.speaker ? `${d.speaker}：${d.text}` : d.text)
          .join('\n');

        return {
          segmentIndex: idx,
          title: `分镜 ${idx + 1}`,
          synopsis: info.composition || '',
          sceneThumbnail: info.backgroundUrl || null,
          characterAvatars,
          pipelineStep: 'pending' as const,
          comicUrl: null,
          videoUrl: null,
          feedback: '',
          panelData: {
            panelId: String(panel.id),
            shotType: info.shot_type,
            cameraAngle: info.camera_angle,
            pacing: info.pacing,
            dialogue,
            characters: info.characters || [],
            background: info.background || {},
            imagePromptHint: info.image_prompt_hint,
            sfx: info.sfx || [],
          },
        };
      });

      setChapters(prev =>
        prev.map(ch => ({
          ...ch,
          episodes: ch.episodes.map(ep =>
            ep.episodeId === episodeId ? { ...ep, segments } : ep
          ),
        }))
      );
    } catch (err) {
      console.error(`加载集 ${episodeId} 分镜失败:`, err);
    }

    // 加载涉及的角色头像
    if (charIds.length > 0) {
      loadCharacterAvatars(charIds);
    }
  }, [projectId, charAvatarMap]);

  /**
   * 手动刷新分镜（清除缓存重新加载）
   */
  const handleRefreshPanels = useCallback(async (episodeId: number) => {
    panelsLoadedRef.current.delete(episodeId);
    await loadPanelsForEpisode(episodeId);
  }, [loadPanelsForEpisode]);

  /**
   * 切换集数展开/收起（手风琴模式）
   */
  const toggleEpisode = useCallback((episodeId: number) => {
    setExpansion(prev => {
      const willExpand = prev.expandedEpisodeId !== episodeId;
      return {
        expandedEpisodeId: willExpand ? episodeId : null,
        expandedSegmentKey: null,
      };
    });
  }, []);

  // charAvatarMap 更新后，刷新已有 segments 的角色头像
  useEffect(() => {
    if (Object.keys(charAvatarMap).length === 0) return;
    setChapters(prev =>
      prev.map(ch => ({
        ...ch,
        episodes: ch.episodes.map(ep => ({
          ...ep,
          segments: ep.segments.map(seg => ({
            ...seg,
            characterAvatars: seg.characterAvatars.map(a => ({
              ...a,
              avatarUrl: charAvatarMap[a.name] || a.avatarUrl,
            })),
          })),
        })),
      }))
    );
  }, [charAvatarMap]);

  // 展开集数时自动加载分镜
  useEffect(() => {
    if (expansion.expandedEpisodeId !== null) {
      loadPanelsForEpisode(expansion.expandedEpisodeId);
    }
  }, [expansion.expandedEpisodeId, loadPanelsForEpisode]);

  /**
   * 生成分镜（异步任务，轮询状态）
   */
  const handleGeneratePanels = useCallback(async (episodeId: number) => {
    if (!projectId || generatingEpisodeId !== null) return;
    setGeneratingEpisodeId(episodeId);
    try {
      const res = await generatePanels(projectId, episodeId);
      // 400 且提示"已有任务在执行中"，视为成功，直接进入轮询
      const isAlreadyRunning =
        (res.code === 400 && res.message?.includes('已有分镜生成任务在执行中')) ||
        (res.code === 400 && res.message?.includes('already'));

      if (res.code !== 0 && res.code !== 200 && !isAlreadyRunning) {
        alert(res.message || '生成分镜失败');
        setGeneratingEpisodeId(null);
        return;
      }
      // 从响应中获取 jobId
      const jobId = res.data?.jobId || res.data?.id || String(res.data);
      if (!jobId || jobId === 'null' || jobId === 'undefined') {
        // 无 jobId，直接刷新分镜
        panelsLoadedRef.current.delete(episodeId);
        await loadPanelsForEpisode(episodeId);
        setGeneratingEpisodeId(null);
        return;
      }
      // 轮询任务状态
      const poll = async () => {
        const MAX_POLL = 60;
        for (let i = 0; i < MAX_POLL; i++) {
          await new Promise(r => setTimeout(r, 3000));
          try {
            const statusRes = await getPanelGenerateStatus(projectId, episodeId, jobId);
            if (statusRes.code !== 0 && statusRes.code !== 200) continue;
            const status = statusRes.data?.status || statusRes.data?.state;
            if (status === 'completed' || status === 'COMPLETED' || status === 'SUCCESS') {
              panelsLoadedRef.current.delete(episodeId);
              await loadPanelsForEpisode(episodeId);
              setGeneratingEpisodeId(null);
              return;
            }
            if (status === 'failed' || status === 'FAILED' || status === 'ERROR') {
              alert('分镜生成失败');
              setGeneratingEpisodeId(null);
              return;
            }
          } catch {
            // 轮询失败继续重试
          }
        }
        alert('生成超时，请稍后刷新');
        setGeneratingEpisodeId(null);
      };
      poll();
    } catch (err: any) {
      // 400 "已有任务在执行中" 也会抛异常，同样进入轮询
      const msg = err?.response?.data?.message || err?.message || '';
      const isAlreadyRunning =
        msg.includes('已有分镜生成任务在执行中') || msg.includes('already');
      if (isAlreadyRunning) {
        // 无法获取 jobId，直接定时刷新分镜
        const poll = async () => {
          const MAX_POLL = 60;
          for (let i = 0; i < MAX_POLL; i++) {
            await new Promise(r => setTimeout(r, 5000));
            panelsLoadedRef.current.delete(episodeId);
            await loadPanelsForEpisode(episodeId);
            // 如果分镜数据不再是空，说明生成完成
            const chaps = await new Promise<ChapterState[]>(resolve => {
              setChapters(prev => { resolve(prev); return prev; });
            });
            const ep = chaps.flatMap(c => c.episodes).find(e => e.episodeId === episodeId);
            if (ep && ep.segments.length > 0) {
              setGeneratingEpisodeId(null);
              return;
            }
          }
          setGeneratingEpisodeId(null);
        };
        poll();
        return;
      }
      alert(msg || '生成分镜失败');
      setGeneratingEpisodeId(null);
    }
  }, [projectId, generatingEpisodeId, loadPanelsForEpisode]);

  /**
   * 切换片段展开/收起
   */
  const toggleSegment = useCallback((segmentKey: string | null) => {
    setExpansion(prev => ({
      ...prev,
      expandedSegmentKey: segmentKey,
    }));
  }, []);

  /**
   * 生成背景图
   */
  const handleGenerateBackground = useCallback(async (episodeId: number, panelId: string) => {
    if (!projectId || !panelId) return;
    setGeneratingBackgroundPanelId(panelId);
    try {
      const res = await generateBackground(projectId, episodeId, Number(panelId));
      if (res.code !== 0 && res.code !== 200) {
        alert(res.message || '生成背景图失败');
      }
    } catch (err: any) {
      alert(err?.message || '生成背景图失败');
    } finally {
      setGeneratingBackgroundPanelId(null);
    }
  }, [projectId]);

  /**
   * 修改分镜脚本
   */
  const handleRevisePanel = useCallback(async (episodeId: number) => {
    if (!projectId) return;
    const feedback = prompt('请输入修改意见：');
    if (!feedback?.trim()) return;
    setRevisingEpisodeId(episodeId);
    try {
      const res = await revisePanel(projectId, episodeId, 0, feedback.trim());
      if (res.code !== 0 && res.code !== 200) {
        alert(res.message || '修改分镜失败');
      }
    } catch (err: any) {
      alert(err?.message || '修改分镜失败');
    } finally {
      setRevisingEpisodeId(null);
    }
  }, [projectId]);

  /**
   * 渲染集数卡片
   */
  const renderEpisodeCard = (chapterIndex: number, episode: EpisodeState) => {
    const isExpanded = expansion.expandedEpisodeId === episode.episodeId;

    return (
      <EpisodeCard
        key={episode.episodeId}
        chapterIndex={chapterIndex}
        episode={episode}
        isExpanded={isExpanded}
        onToggle={() => toggleEpisode(episode.episodeId)}
        expandedSegmentKey={expansion.expandedSegmentKey}
        onSegmentToggle={toggleSegment}
        onSegmentApprove={() => {}}
        onSegmentRegenerate={() => {}}
        onSegmentGenerateVideo={() => {}}
        onGeneratePanels={handleGeneratePanels}
        isGeneratingPanels={generatingEpisodeId === episode.episodeId}
        onRefreshPanels={handleRefreshPanels}
        onGenerateBackground={handleGenerateBackground}
        generatingBackgroundPanelId={generatingBackgroundPanelId}
        onRevisePanel={handleRevisePanel}
        isRevisingPanel={revisingEpisodeId === episode.episodeId}
      />
    );
  };

  /**
   * 渲染片段完成状态圆点
   */
  const renderCompletionDot = (step: SegmentState['pipelineStep']) => {
    if (step === 'video_completed') {
      return <span className={styles.dotCompleted} />;
    }
    if (step === 'video_generating' || step === 'comic_review' || step === 'comic_approved') {
      return <span className={styles.dotInProgress} />;
    }
    return <span className={styles.dotPending} />;
  };

  // 加载中状态
  if (loading) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.loadingState}>
          <div className={styles.spinner} />
          <p>加载中...</p>
        </div>
      </div>
    );
  }

  // 错误状态
  if (error) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.errorState}>
          <p>{error}</p>
          <button onClick={loadEpisodes} className={styles.retryButton}>
            重试
          </button>
        </div>
      </div>
    );
  }

  // 空状态
  if (chapters.length === 0) {
    return (
      <div className={styles.pageContainer}>
        <div className={styles.emptyState}>
          <p>暂无章节数据</p>
        </div>
      </div>
    );
  }

  return (
    <div className={styles.pageContainer}>
      {/* 顶部标题和统计栏 */}
      <div className={styles.pageHeader}>
        <div className={styles.titleSection}>
          <h1 className={styles.pageTitle}>视频生产</h1>
          <p className={styles.pageSubtitle}>管理剧集、片段和视频生成流程</p>
        </div>
        <div className={styles.statsBar}>
          <div className={styles.statsInfo}>
            <span className={styles.completedCount}>{completedSegments}</span>
            <span className={styles.separator}>/</span>
            <span className={styles.totalCount}>{totalSegments}</span>
            <span className={styles.statsLabel}>片段已完成</span>
          </div>
          <button className={styles.generateAllButton} disabled>
            一键生成
          </button>
        </div>
      </div>

      {/* 章节列表 */}
      <div className={styles.chapterList}>
        {chapters.map(chapter => {
          const chapterSegments = chapter.episodes.reduce(
            (sum, ep) => sum + ep.segments.length,
            0
          );
          const chapterCompleted = chapter.episodes.reduce(
            (sum, ep) =>
              sum + ep.segments.filter(seg => seg.pipelineStep === 'video_completed').length,
            0
          );

          return (
            <div key={chapter.chapterIndex} className={styles.chapterGroup}>
              <div className={styles.chapterHeader}>
                <div className={styles.chapterTitleRow}>
                  <span className={styles.chapterIcon}>📖</span>
                  <h2 className={styles.chapterTitle}>
                    第{chapter.chapterIndex}章 {chapter.title}
                  </h2>
                  <div className={styles.chapterDivider} />
                  <div className={styles.chapterStats}>
                    {chapter.episodes.map(ep =>
                      ep.segments.map(seg => (
                        <span key={`${ep.episodeId}-${seg.segmentIndex}`} className={styles.statDot}>
                          {renderCompletionDot(seg.pipelineStep)}
                        </span>
                      ))
                    )}
                  </div>
                </div>
                <div className={styles.chapterProgress}>
                  <span className={styles.chapterProgressText}>
                    {chapterCompleted} / {chapterSegments}
                  </span>
                </div>
              </div>

              <div className={styles.episodeList}>
                {chapter.episodes.map(ep => renderEpisodeCard(chapter.chapterIndex, ep))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default Step5page;
