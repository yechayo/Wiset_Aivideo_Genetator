import { useEffect, useState, useRef, useCallback } from 'react';
import styles from './Step2page.module.less';
import type { Project, ScriptContentResponse } from '../../../services';
import {
  getScript,
  generateScript,
  generateEpisodes,
  reviseEpisodes,
  confirmScript,
  generateAllEpisodes,
  reviseScript,
  updateScriptOutline,
  isApiSuccess,
} from '../../../services';
import type { StepContentProps } from '../types';
import { useProjectStore } from '../../../stores';
import { useCreateStore } from '../../../stores/createStore';
import { useSseProgress } from './hooks/useSseProgress';
import OutlineEditor from './components/OutlineEditor';
import ChapterList from './components/ChapterList';
import GenerateEpisodesDialog from './components/GenerateEpisodesDialog';

// ---------------------------------------------------------------------------
// Phase constants
// ---------------------------------------------------------------------------
type Phase =
  | 'outline_generating'  // 大纲正在生成（AI 中）
  | 'outline_review'      // 大纲已生成，等待用户审核
  | 'episode_generating'  // 剧情正在批量/逐章生成
  | 'episode_review';     // 剧情已生成，等待用户确认

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------
interface Step2pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step 2: 大纲生成/审核 + 剧情生成/确认
 *
 * 四个阶段完全由后端 statusInfo.statusCode 驱动：
 *   - draft / null                    → outline_generating
 *   - outline_review                   → outline_review
 *   - outline_confirmed                → episode_generating（若还有 pendingChapters）
 *   - episode_review                   → episode_review
 *   - episode_confirmed                → 自动跳转 Step 3（由 CreateLayout 处理）
 */
const Step2page = ({ project, onComplete }: Step2pageProps) => {
  // ======== 数据状态 ========
  const [scriptData, setScriptData] = useState<ScriptContentResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 剧集生成对话框
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedChapter, setSelectedChapter] = useState<string | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [isBatchGenerating, setIsBatchGenerating] = useState(false);
  const [isSavingOutline, setIsSavingOutline] = useState(false);

  // 轮询
  const maxPollingCount = 45;
  const pollingRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pollingCountRef = useRef(0);
  // 防止重复自动触发大纲生成
  const autoTriggerRef = useRef(false);

  // Store
  const getProjectId = useProjectStore((state) => state.getProjectId);
  const { statusInfo } = useCreateStore();

  // 稳定的项目 ID
  const projectIdRef = useRef<string | null>(
    getProjectId() || project.projectId || (project.id ? String(project.id) : null)
  );

  // ======== Phase 推导 ========
  const phase: Phase = derivePhase(statusInfo?.statusCode, scriptData);

  // ======== SSE 订阅 ========
  useSseProgress(projectIdRef.current, {
    onEpisodeScriptDone(data) {
      // 某集生成完成，刷新 script 数据
      refreshScript();
    },
    onStatusChange() {
      // 里程碑变更，刷新 script 数据
      refreshScript();
    },
    onReconnect() {
      refreshScript();
    },
    onEpisodePanelDone() {},
    onEpisodeGridStatus() {},
  });

  // ======== 刷新剧本数据 ========
  const refreshScript = useCallback(async () => {
    const pid = projectIdRef.current;
    if (!pid) return;
    try {
      const result = await getScript(pid);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
    } catch (err) {
      console.error('刷新剧本失败:', err);
    }
  }, []);

  // ======== 轮询拉取（首次加载 / 大纲生成等待） ========
  const fetchScriptWithPolling = useCallback((attempt: number = 0, quickCheck: boolean = false) => {
    const pid = projectIdRef.current;
    if (!pid) {
      setError('无法获取项目 ID');
      setIsLoading(false);
      return;
    }
    if (attempt === 0) {
      setIsLoading(true);
      pollingCountRef.current = 0;
    }
    setError(null);

    // quickCheck 模式：最多尝试 2 次（4秒），快速判断是否有已有数据
    const maxAttempts = quickCheck ? 2 : maxPollingCount;

    const doFetch = async () => {
      try {
        const result = await getScript(pid);

        if (isApiSuccess(result) && result.data && result.data.outline) {
          setScriptData(result.data);
          if (pollingRef.current) {
            clearTimeout(pollingRef.current);
            pollingRef.current = null;
          }
          setIsLoading(false);
        } else if (isApiSuccess(result) && result.data) {
          // 有 scriptData 但无大纲 → 更新数据（用于 pendingChapters 等信息），继续轮询等待大纲
          setScriptData(result.data);
          if (pollingCountRef.current < maxAttempts) {
            pollingCountRef.current++;
            pollingRef.current = setTimeout(() => doFetch(), 2000);
          } else {
            setIsLoading(false);
          }
        } else {
          // 无数据 → 继续轮询（可能还在生成）
          if (pollingCountRef.current < maxAttempts) {
            pollingCountRef.current++;
            pollingRef.current = setTimeout(() => doFetch(), 2000);
          } else {
            setScriptData(null);
            setIsLoading(false);
          }
        }
      } catch {
        if (pollingCountRef.current < maxAttempts) {
          pollingCountRef.current++;
          pollingRef.current = setTimeout(() => doFetch(), 2000);
        } else {
          setError('获取剧本失败，请稍后重试');
          setIsLoading(false);
        }
      }
    };

    doFetch();
  }, []);

  // 首次 mount：快速检查是否有已有数据（最多 4 秒），随后 autoTrigger effect 负责自动生成
  useEffect(() => {
    fetchScriptWithPolling(0, true);
    return () => {
      if (pollingRef.current) {
        clearTimeout(pollingRef.current);
        pollingRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 监听 isGenerating 变化（后端从 generating → idle 时刷新数据）
  const prevGeneratingRef = useRef(statusInfo?.isGenerating ?? false);
  useEffect(() => {
    const wasGenerating = prevGeneratingRef.current;
    const nowGenerating = statusInfo?.isGenerating ?? false;
    prevGeneratingRef.current = nowGenerating;

    // 仅在 从 true 变为 false 且大纲数据缺失时触发
    if (wasGenerating && !nowGenerating && !scriptData?.outline) {
      const pid = projectIdRef.current;
      if (!pid) return;
      setIsLoading(true);
      getScript(pid)
        .then((result) => {
          if (isApiSuccess(result) && result.data) {
            setScriptData(result.data);
          }
        })
        .catch((err) => {
          console.error('生成完成后拉取剧本失败:', err);
        })
        .finally(() => {
          setIsLoading(false);
        });
    }
  }, [statusInfo?.isGenerating, refreshScript]);

  // 当 statusCode 变为 outline_review 且还没 scriptData 时，主动刷新
  useEffect(() => {
    if (statusInfo?.statusCode === 'outline_review' && !scriptData) {
      refreshScript();
    }
  }, [statusInfo?.statusCode, scriptData, refreshScript]);

  // ======== Phase 1: 触发大纲生成 ========
  const handleGenerateOutline = useCallback(async () => {
    const pid = projectIdRef.current;
    if (!pid) return;

    setIsLoading(true);
    setError(null);
    try {
      const result = await generateScript(pid);
      if (!isApiSuccess(result)) {
        setError('触发大纲生成失败');
        setIsLoading(false);
        return;
      }
      // 生成已提交，开始轮询等待结果
      fetchScriptWithPolling();
    } catch (err) {
      console.error('触发大纲生成失败:', err);
      setError('触发大纲生成失败，请稍后重试');
      setIsLoading(false);
    }
  }, [fetchScriptWithPolling]);

  // 自动触发大纲生成：首次进入且无大纲时自动生成
  useEffect(() => {
    if (
      phase === 'outline_generating' &&
      !isLoading &&
      !scriptData?.outline &&
      !error &&
      !autoTriggerRef.current
    ) {
      autoTriggerRef.current = true;
      handleGenerateOutline();
    }
  }, [phase, isLoading, scriptData, error, handleGenerateOutline]);

  // ======== Phase 2: 大纲审核操作 ========
  const handleSaveOutlineDirect = useCallback(async (content: string) => {
    const pid = projectIdRef.current;
    if (!pid) return;
    setIsSavingOutline(true);
    try {
      await updateScriptOutline(pid, content);
      // 更新本地 scriptData
      setScriptData((prev) => (prev ? { ...prev, outline: content } : prev));
    } catch (err) {
      console.error('保存大纲失败:', err);
      alert('保存大纲失败，请重试');
    } finally {
      setIsSavingOutline(false);
    }
  }, []);

  const handleSaveOutlineWithAI = useCallback(async (content: string, revisionNote: string) => {
    const pid = projectIdRef.current;
    if (!pid) return;
    setIsSavingOutline(true);
    try {
      await reviseScript(pid, {
        revisionNote,
        currentOutline: content,
      });
      // AI 修订已提交，轮询等待新大纲
      fetchScriptWithPolling();
    } catch (err) {
      console.error('AI 修订失败:', err);
      alert('AI 修订失败，请重试');
      setIsSavingOutline(false);
    }
  }, [fetchScriptWithPolling]);

  const handleConfirmOutline = useCallback(async () => {
    const pid = projectIdRef.current;
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }
    const confirmed = window.confirm('确认大纲后将进入剧情生成阶段，确认内容无误后再继续。');
    if (!confirmed) return;

    setIsLoading(true);
    try {
      await confirmScript(pid);
      // 后端 milestone 变更会通过 SSE / 轮询自动同步
      await refreshScript();
    } catch (err) {
      console.error('确认大纲失败:', err);
      setError('确认大纲失败，请稍后重试');
    } finally {
      setIsLoading(false);
    }
  }, [refreshScript]);

  // ======== Phase 3: 剧情生成操作 ========
  const handleGenerateAll = useCallback(async () => {
    if (!scriptData || scriptData.pendingChapters.length === 0) return;
    const confirmed = window.confirm(
      `即将生成全部 ${scriptData.pendingChapters.length} 个剩余章节，这可能需要几分钟时间。是否继续？`
    );
    if (!confirmed) return;

    const pid = projectIdRef.current;
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }
    setIsBatchGenerating(true);
    try {
      await generateAllEpisodes(pid);
      // 生成完成后刷新数据
      await refreshScript();
    } catch (err) {
      console.error('批量生成失败:', err);
      setError('批量生成失败，请稍后重试或尝试逐章生成。');
    } finally {
      setIsBatchGenerating(false);
    }
  }, [scriptData, refreshScript]);

  const handleGenerateClick = useCallback((chapter: string) => {
    setSelectedChapter(chapter);
    setDialogOpen(true);
  }, []);

  const handleGenerateConfirm = useCallback(async (episodeCount: number, modificationSuggestion?: string) => {
    if (!selectedChapter) return;
    const pid = projectIdRef.current;
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }
    setIsGenerating(true);
    try {
      const isRegenerate = scriptData?.generatedChapters?.includes(selectedChapter);
      if (isRegenerate) {
        await reviseEpisodes(pid, {
          chapter: selectedChapter,
          episodeCount,
          modificationSuggestion,
        });
      } else {
        await generateEpisodes(pid, {
          chapter: selectedChapter,
          episodeCount,
          modificationSuggestion,
        });
      }
      await refreshScript();
      setDialogOpen(false);
    } catch (err) {
      console.error('生成剧集失败:', err);
      setError('生成剧集失败，请稍后重试');
    } finally {
      setIsGenerating(false);
    }
  }, [selectedChapter, scriptData, refreshScript]);

  // ======== Phase 4: 确认剧情 ========
  const { syncStatus } = useCreateStore();

  const handleConfirmEpisodes = useCallback(async () => {
    const pid = projectIdRef.current;
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }
    const confirmed = window.confirm('确认剧情后将进入下一步，无法再返回修改。请确认内容无误后再继续。');
    if (!confirmed) return;

    setIsLoading(true);
    try {
      await confirmScript(pid);
      // 立即同步状态，确保 statusInfo 更新后再导航
      await syncStatus(pid);
      onComplete?.();
    } catch (err) {
      console.error('确认剧情失败:', err);
      setError('确认剧情失败，请稍后重试');
    } finally {
      setIsLoading(false);
    }
  }, [onComplete, syncStatus]);

  // ======== 辅助函数 ========
  const getProjectInfo = () => {
    if (!scriptData?.project) return null;
    const p = scriptData.project;
    const outline = scriptData.outline ?? '';
    const titleMatch = outline.match(/^# (.+)$/m);
    const title = titleMatch ? titleMatch[1] : '未命名剧集';
    const genreMatch = outline.match(/\*\*类型\*\*:\s*(.+?)\s*\|/);
    const genre = genreMatch ? genreMatch[1] : p.projectInfo?.genre || '未分类';
    const episodes = p.projectInfo?.totalEpisodes || scriptData.chapters.length || 0;
    return { title, genre, episodes };
  };

  const extractEpisodeCountFromChapter = (chapterTitle: string): number | null => {
    const rangeMatch = chapterTitle.match(/(?:第\s*)?(\d+)\s*[-~～—–]\s*(\d+)\s*集/);
    if (rangeMatch) {
      const start = parseInt(rangeMatch[1], 10);
      const end = parseInt(rangeMatch[2], 10);
      if (end >= start) return end - start + 1;
    }
    const singleMatch = chapterTitle.match(/(?:第\s*)?(\d+)\s*集/);
    if (singleMatch) return 1;
    return null;
  };

  const getDefaultEpisodeCount = (chapterTitle: string): number => {
    if (scriptData?.isSingleEpisode || scriptData?.project?.projectInfo?.totalEpisodes === 1) {
      return 1;
    }
    const fromChapter = extractEpisodeCountFromChapter(chapterTitle);
    if (fromChapter && fromChapter > 0) return fromChapter;
    const fallback = scriptData?.project?.projectInfo?.episodesPerChapter;
    if (fallback && fallback > 0) return fallback;
    return 4;
  };

  const projectInfo = scriptData ? getProjectInfo() : null;

  // ======== 渲染 ========
  return (
    <div className={styles.content}>
      {/* 标题区域 */}
      <div className={styles.header}>
        <h1 className={styles.title}>
          {phase === 'outline_generating' || phase === 'outline_review'
            ? '剧本大纲'
            : '剧情生成'}
        </h1>
        <p className={styles.subtitle}>
          {phase === 'outline_generating' && 'AI 正在根据你的创意生成剧本大纲...'}
          {phase === 'outline_review' && '审阅 AI 生成的大纲，确认无误后进入剧情生成'}
          {phase === 'episode_generating' && 'AI 正在根据大纲生成各章节剧情...'}
          {phase === 'episode_review' && '审阅生成的剧情内容，确认后进入角色设定'}
        </p>
      </div>

      {/* Phase 1: 大纲生成中 */}
      {phase === 'outline_generating' && (
        <>
          {isLoading && (
            <div className={styles.loadingState}>
              <div className={styles.spinner}></div>
              <p>AI 正在生成剧本大纲，请稍候...</p>
            </div>
          )}

          {!isLoading && error && (
            <div className={styles.errorState}>
              <p>{error}</p>
              <button className={styles.retryButton} onClick={handleGenerateOutline}>
                重新生成大纲
              </button>
            </div>
          )}

          {!isLoading && !error && !scriptData?.outline && (
            <div className={styles.emptyState}>
              <p>暂无大纲数据</p>
              <p className={styles.emptyHint}>点击下方按钮开始生成</p>
              <button className={styles.confirmButton} onClick={handleGenerateOutline}>
                生成剧本大纲
              </button>
            </div>
          )}
        </>
      )}

      {/* Phase 2: 大纲审核 */}
      {phase === 'outline_review' && (
        <>
          {isLoading && !scriptData && (
            <div className={styles.loadingState}>
              <div className={styles.spinner}></div>
              <p>正在加载大纲...</p>
            </div>
          )}

          {error && (
            <div className={styles.errorState}>
              <p>{error}</p>
              <button className={styles.retryButton} onClick={refreshScript}>
                重试
              </button>
            </div>
          )}

          {scriptData && (
            <div className={styles.scriptContainer}>
              {/* 项目信息 */}
              {projectInfo && (
                <div className={styles.projectInfo}>
                  <div className={styles.infoItem}>
                    <span className={styles.infoLabel}>剧名：</span>
                    <span className={styles.infoValue}>{projectInfo.title}</span>
                  </div>
                  <div className={styles.infoItem}>
                    <span className={styles.infoLabel}>类型：</span>
                    <span className={styles.infoValue}>{projectInfo.genre}</span>
                  </div>
                  <div className={styles.infoItem}>
                    <span className={styles.infoLabel}>集数：</span>
                    <span className={styles.infoValue}>{projectInfo.episodes} 集</span>
                  </div>
                </div>
              )}

              {/* 大纲编辑器（可编辑） */}
              <OutlineEditor
                outline={scriptData.outline}
                onSaveDirect={handleSaveOutlineDirect}
                onSaveWithAI={handleSaveOutlineWithAI}
                saving={isSavingOutline}
              />
            </div>
          )}

          {/* 底部固定操作栏：确认大纲 */}
          <div className={styles.bottomActionBar}>
            <button
              className={styles.confirmButton}
              onClick={handleConfirmOutline}
              disabled={isLoading || !scriptData}
            >
              确认大纲，进入剧情生成
            </button>
          </div>
        </>
      )}

      {/* Phase 3: 剧情生成 */}
      {(phase === 'episode_generating' || phase === 'episode_review') && (
        <>
          {isLoading && !scriptData && (
            <div className={styles.loadingState}>
              <div className={styles.spinner}></div>
              <p>正在加载剧本数据...</p>
            </div>
          )}

          {error && (
            <div className={styles.errorState}>
              <p>{error}</p>
              <button className={styles.retryButton} onClick={refreshScript}>
                重试
              </button>
            </div>
          )}

          {scriptData && (
            <div className={styles.scriptContainer}>
              {/* 项目信息 */}
              {projectInfo && (
                <div className={styles.projectInfo}>
                  <div className={styles.infoItem}>
                    <span className={styles.infoLabel}>剧名：</span>
                    <span className={styles.infoValue}>{projectInfo.title}</span>
                  </div>
                  <div className={styles.infoItem}>
                    <span className={styles.infoLabel}>类型：</span>
                    <span className={styles.infoValue}>{projectInfo.genre}</span>
                  </div>
                  <div className={styles.infoItem}>
                    <span className={styles.infoLabel}>集数：</span>
                    <span className={styles.infoValue}>{projectInfo.episodes} 集</span>
                  </div>
                </div>
              )}

              {/* 大纲（只读展示） */}
              <OutlineEditor outline={scriptData.outline} readOnly />

              {/* 批量生成按钮（仅在有待生成章节时显示） */}
              {scriptData.pendingChapters.length > 0 && (
                <div className={styles.batchAction}>
                  <button
                    className={styles.batchGenerateButton}
                    onClick={handleGenerateAll}
                    disabled={isBatchGenerating || isGenerating || !!statusInfo?.isGenerating}
                  >
                    {isBatchGenerating || isGenerating || !!statusInfo?.isGenerating
                      ? '批量生成中...'
                      : (scriptData.generatedChapters?.length > 0
                        ? `生成剩余剧集 (${scriptData.pendingChapters.length} 章)`
                        : `一键生成全部剧集 (${scriptData.pendingChapters.length} 章)`)}
                  </button>
                </div>
              )}

              {/* 章节列表 */}
              <ChapterList
                chapters={scriptData.chapters}
                generatedChapters={scriptData.generatedChapters}
                pendingChapters={scriptData.pendingChapters}
                episodes={scriptData.episodes}
                onGenerateClick={handleGenerateClick}
                isBatchGenerating={isBatchGenerating || !!statusInfo?.isGenerating}
              />
            </div>
          )}

          {/* Phase 4: 确认剧情按钮（所有章节已生成时显示） */}
          {phase === 'episode_review' && (
            <div className={styles.buttonContainer}>
              <button
                className={styles.confirmButton}
                onClick={handleConfirmEpisodes}
                disabled={isLoading || !scriptData || scriptData.pendingChapters.length > 0}
              >
                {!scriptData || scriptData.pendingChapters.length > 0
                  ? `全部章节已生成后可确认 (剩余 ${scriptData?.pendingChapters.length || 0} 章)`
                  : '确认剧情，进入下一步'}
              </button>
            </div>
          )}
        </>
      )}

      {/* 生成剧集对话框 */}
      <GenerateEpisodesDialog
        open={dialogOpen}
        chapter={selectedChapter || ''}
        defaultEpisodeCount={getDefaultEpisodeCount(selectedChapter || '')}
        onClose={() => setDialogOpen(false)}
        onConfirm={handleGenerateConfirm}
        loading={isGenerating}
      />
    </div>
  );
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * 从后端 statusCode + scriptData 推导当前阶段
 */
function derivePhase(
  statusCode: string | undefined,
  scriptData: ScriptContentResponse | null,
): Phase {
  switch (statusCode) {
    case 'outline_review':
      return 'outline_review';
    case 'outline_confirmed':
      // 大纲已确认 → 如果还有 pendingChapters 则 episode_generating，否则 episode_review
      // 但 outline_confirmed 时后端 statusCode 会变成 episode_review 生成后
      // 这里先按 episode_generating 处理，后续 statusCode 更新会自动切换
      return 'episode_generating';
    case 'episode_review':
      return 'episode_review';
    case 'episode_confirmed':
      // 已确认 → 由 CreateLayout 路由守卫跳转 Step 3
      return 'episode_review';
    case 'draft':
    default:
      // statusCode 为 undefined 时（store 未加载），以本地 scriptData 为准
      if (scriptData?.outline) {
        return 'outline_review';
      }
      return 'outline_generating';
  }
}

export default Step2page;