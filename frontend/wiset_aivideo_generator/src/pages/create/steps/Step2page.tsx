import { useEffect, useState, useRef, useCallback } from 'react';
import styles from './Step2page.module.less';
import type { Project, ScriptContentResponse } from '../../../services';
import { getScript, generateEpisodes, confirmScript, reviseScript, updateScriptOutline, generateAllEpisodes, isApiSuccess } from '../../../services';
import type { StepContentProps } from '../types';
import { useProjectStore } from '../../../stores';
import { useCreateStore } from '../../../stores/createStore';
import { useTransitionOverlay } from '../CreateLayout';
import OutlineEditor from './components/OutlineEditor';
import ChapterList from './components/ChapterList';
import GenerateEpisodesDialog from './components/GenerateEpisodesDialog';

interface Step2pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step 2: 剧本编辑
 */
const Step2page = ({ project, onComplete }: Step2pageProps) => {
  const [scriptData, setScriptData] = useState<ScriptContentResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 轮询相关状态
  const maxPollingCount = 45; // 最多轮询45次（90秒），覆盖 AI 生成耗时
  const pollingRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 生成剧集对话框状态
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedChapter, setSelectedChapter] = useState<string | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [isBatchGenerating, setIsBatchGenerating] = useState(false);

  const getProjectId = useProjectStore((state) => state.getProjectId);

  // Step2 mount 时通知 CreateLayout 隐藏过渡遮罩
  const { hideTransitionOverlay } = useTransitionOverlay();
  // 用 ref 持有最新的 hideTransitionOverlay，避免将其加入 effect 依赖导致重复触发
  const hideOverlayRef = useRef(hideTransitionOverlay);
  useEffect(() => { hideOverlayRef.current = hideTransitionOverlay; });

  // 稳定的项目 ID —— 只在首次 mount 时计算，避免 project 对象引用变化触发 effect
  const projectIdRef = useRef<string | null>(
    getProjectId() || project.projectId || (project.id ? String(project.id) : null)
  );

  useEffect(() => {
    const pid = projectIdRef.current;

    const fetchScript = async (attempt: number = 0) => {
      if (!pid) {
        setError('无法获取项目 ID');
        setIsLoading(false);
        hideOverlayRef.current();
        return;
      }

      // 第一次尝试时设置 loading
      if (attempt === 0) {
        setIsLoading(true);
      }
      setError(null);

      try {
        const result = await getScript(pid);
        console.log('剧本数据:', result);

        if (isApiSuccess(result) && result.data) {
          // 有数据，清除轮询
          setScriptData(result.data);
          if (pollingRef.current) {
            clearTimeout(pollingRef.current);
            pollingRef.current = null;
          }
          setIsLoading(false);
          // 数据就绪后才隐藏过渡遮罩，避免显示空白加载状态
          hideOverlayRef.current();
        } else {
          // 无数据，检查是否需要轮询
          if (attempt < maxPollingCount) {
            // 等待2秒后重试
            pollingRef.current = setTimeout(() => {
              fetchScript(attempt + 1);
            }, 2000);
            return; // 保持 loading 状态（过渡遮罩继续显示）
          } else {
            // 超过最大轮询次数，显示空状态
            setScriptData(null);
            setIsLoading(false);
            hideOverlayRef.current();
            console.warn('剧本数据为空或未生成，已达到最大轮询次数');
          }
        }
      } catch (err) {
        console.error('获取剧本失败:', err);
        if (attempt < maxPollingCount) {
          // 网络错误时也重试
          pollingRef.current = setTimeout(() => {
            fetchScript(attempt + 1);
          }, 2000);
          return; // 保持 loading 状态（过渡遮罩继续显示）
        } else {
          setError('获取剧本失败，请稍后重试');
          setIsLoading(false);
          hideOverlayRef.current();
        }
      }
    };

    fetchScript();

    // 清理函数
    return () => {
      if (pollingRef.current) {
        clearTimeout(pollingRef.current);
        pollingRef.current = null;
      }
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // 只在 mount 时执行一次，projectId 通过 ref 读取

  // 监听后端生成状态：isGenerating 从 true → false 时，立即重新拉取剧本数据
  const { statusInfo } = useCreateStore();
  const prevGeneratingRef = useRef(statusInfo?.isGenerating ?? false);
  useEffect(() => {
    const wasGenerating = prevGeneratingRef.current;
    const nowGenerating = statusInfo?.isGenerating ?? false;
    prevGeneratingRef.current = nowGenerating;

    // 仅在 从 true 变为 false 时触发
    if (wasGenerating && !nowGenerating && !scriptData) {
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
          hideOverlayRef.current();
        });
    }
  }, [statusInfo?.isGenerating, scriptData]);

  // 获取当前项目 ID（用于事件处理函数）
  const getCurrentProjectId = useCallback(() => {
    return projectIdRef.current;
  }, []);

  // 直接保存用户编辑的大纲
  const handleOutlineSaveDirect = async (content: string) => {
    const pid = getCurrentProjectId();
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }
    setIsLoading(true);
    try {
      await updateScriptOutline(pid, content);
      const result = await getScript(pid);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
    } catch (err) {
      console.error('保存大纲失败:', err);
      setError('保存大纲失败，请稍后重试');
    } finally {
      setIsLoading(false);
    }
  };

  // AI 重新生成大纲
  const handleOutlineSaveWithAI = async (content: string, revisionNote: string) => {
    const pid = getCurrentProjectId();
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }
    setIsLoading(true);
    try {
      await reviseScript(pid, {
        revisionNote: revisionNote,
        currentOutline: content
      });
      const result = await getScript(pid);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
    } catch (err) {
      console.error('AI 重新生成失败:', err);
      setError('AI 重新生成失败，请稍后重试');
    } finally {
      setIsLoading(false);
    }
  };

  const handleGenerateAll = async () => {
    if (!scriptData || scriptData.pendingChapters.length === 0) return;
    const confirmed = window.confirm(
      `即将生成全部 ${scriptData.pendingChapters.length} 个剩余章节，这可能需要几分钟时间。是否继续？`
    );
    if (!confirmed) return;

    const pid = getCurrentProjectId();
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }
    setIsBatchGenerating(true);
    setIsLoading(true);
    try {
      await generateAllEpisodes(pid);
      const result = await getScript(pid);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
    } catch (err) {
      console.error('批量生成失败:', err);
      setError('批量生成失败，请稍后重试或尝试逐章生成。');
    } finally {
      setIsBatchGenerating(false);
      setIsLoading(false);
    }
  };

  // 处理生成剧集点击
  const handleGenerateClick = (chapter: string) => {
    setSelectedChapter(chapter);
    setDialogOpen(true);
  };

  // 处理生成剧集确认（支持新生成和重新生成）
  const handleGenerateConfirm = async (episodeCount: number, modificationSuggestion?: string) => {
    if (!selectedChapter) return;

    const pid = getCurrentProjectId();
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }

    setIsGenerating(true);
    try {
      await generateEpisodes(pid, {
        chapter: parseInt(selectedChapter, 10),
        episodeCount,
        modificationSuggestion
      });
      // 重新获取剧本数据
      const result = await getScript(pid);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
      setDialogOpen(false);
    } catch (err) {
      console.error('生成剧集失败:', err);
      setError('生成剧集失败，请稍后重试');
    } finally {
      setIsGenerating(false);
    }
  };

  // 处理确认
  const handleConfirm = async () => {
    console.log('确认项目:', project);
    console.log('当前剧本:', scriptData);

    const pid = getCurrentProjectId();
    if (!pid) {
      setError('无法获取项目 ID');
      return;
    }

    // 确认前提醒用户
    const confirmed = window.confirm('确认后将进入下一步，无法再返回修改。请确认内容无误后再继续。');
    if (!confirmed) return;

    setIsLoading(true);
    try {
      // 调用确认剧本接口
      await confirmScript(pid);
      // 完成此步骤，进入下一步
      onComplete?.();
    } catch (err) {
      console.error('确认剧本失败:', err);
      setError('确认剧本失败，请稍后重试');
    } finally {
      setIsLoading(false);
    }
  };

  // 提取项目信息显示
  const getProjectInfo = () => {
    if (!scriptData?.project) return null;
    const p = scriptData.project;
    // 从 outline 中提取剧名（outline 可能为 null，需要做防御判断）
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
      if (end >= start) {
        return end - start + 1;
      }
    }

    const singleMatch = chapterTitle.match(/(?:第\s*)?(\d+)\s*集/);
    if (singleMatch) {
      return 1;
    }

    return null;
  };

  const getDefaultEpisodeCount = (chapterTitle: string): number => {
    if (scriptData?.isSingleEpisode || scriptData?.project?.projectInfo?.totalEpisodes === 1) {
      return 1;
    }

    const fromChapter = extractEpisodeCountFromChapter(chapterTitle);
    if (fromChapter && fromChapter > 0) {
      return fromChapter;
    }

    const fallback = scriptData?.project?.projectInfo?.episodesPerChapter;
    if (fallback && fallback > 0) {
      return fallback;
    }

    return 4;
  };

  const projectInfo = scriptData ? getProjectInfo() : null;

  return (
    <div className={styles.content}>
      {/* 标题区域 */}
      <div className={styles.header}>
        <h1 className={styles.title}>剧本编辑</h1>
        <p className={styles.subtitle}>
          编辑 AI 生成的剧本大纲，并生成各章节剧集
        </p>
      </div>

      {/* 加载状态 */}
      {isLoading && (
        <div className={styles.loadingState}>
          <div className={styles.spinner}></div>
          <p>正在加载剧本...</p>
        </div>
      )}

      {/* 错误状态 */}
      {!isLoading && error && (
        <div className={styles.errorState}>
          <p>{error}</p>
          <button
            className={styles.retryButton}
            onClick={() => window.location.reload()}
          >
            重试
          </button>
        </div>
      )}

      {/* 剧本内容 */}
      {!isLoading && !error && scriptData && (
        <div className={styles.scriptContainer}>
          {/* 项目信息卡片 */}
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

          {/* 大纲编辑器 */}
          <OutlineEditor
            outline={scriptData.outline}
            onSaveDirect={handleOutlineSaveDirect}
            onSaveWithAI={handleOutlineSaveWithAI}
          />

          {/* 批量生成按钮 */}
          {scriptData.pendingChapters.length > 0 && (
            <div className={styles.batchAction}>
              <button
                className={styles.batchGenerateButton}
                onClick={handleGenerateAll}
                disabled={isBatchGenerating}
              >
                {isBatchGenerating ? '批量生成中...' : `一键生成全部剩余章节 (${scriptData.pendingChapters.length} 章)`}
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
          />
        </div>
      )}

      {/* 空状态 */}
      {!isLoading && !error && !scriptData && (
        <div className={styles.emptyState}>
          <p>暂无剧本数据</p>
          <p className={styles.emptyHint}>剧本可能还在生成中，请稍后刷新</p>
        </div>
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

      {/* 按钮容器 */}
      <div className={styles.buttonContainer}>
        <button
          className={styles.confirmButton}
          onClick={handleConfirm}
          disabled={isLoading || !scriptData || scriptData.pendingChapters.length > 0}
        >
          {!scriptData || scriptData.pendingChapters.length > 0
            ? `全部章节已生成后可确认 (剩余 ${scriptData?.pendingChapters.length || 0} 章)`
            : '确认剧本，进入下一步'}
        </button>
      </div>
    </div>
  );
};

export default Step2page;
