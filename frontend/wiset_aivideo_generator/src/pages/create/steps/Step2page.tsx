import { useEffect, useState, useRef } from 'react';
import styles from './Step2page.module.less';
import type { Project, ScriptContentResponse } from '../../../services';
import { getScript, generateEpisodes, confirmScript, reviseScript, updateScriptOutline, generateAllEpisodes, isApiSuccess } from '../../../services';
import type { StepContentProps } from '../types';
import { useProjectStore } from '../../../stores';
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
  const maxPollingCount = 10; // 最多轮询10次
  const pollingRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 生成剧集对话框状态
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedChapter, setSelectedChapter] = useState<string | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [isRegenerateMode, setIsRegenerateMode] = useState(false); // 是否为重新生成模式
  const [isBatchGenerating, setIsBatchGenerating] = useState(false);

  const getProjectId = useProjectStore((state) => state.getProjectId);

  useEffect(() => {
    const fetchScript = async (attempt: number = 0) => {
      // 获取项目 ID（优先使用 store 中的，回退到 props 中的）
      const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);

      if (!projectId) {
        setError('无法获取项目 ID');
        setIsLoading(false);
        return;
      }

      // 如果是第一次尝试或非轮询调用，设置loading
      if (attempt === 0) {
        setIsLoading(true);
      }
      setError(null);

      try {
        const result = await getScript(projectId);
        console.log('剧本数据:', result);

        if (isApiSuccess(result) && result.data) {
          // 有数据，清除轮询
          setScriptData(result.data);
          if (pollingRef.current) {
            clearTimeout(pollingRef.current);
            pollingRef.current = null;
          }
          setIsLoading(false);
        } else {
          // 无数据，检查是否需要轮询
          if (attempt < maxPollingCount) {
            // 等待2秒后重试
            pollingRef.current = setTimeout(() => {
              fetchScript(attempt + 1); // 递归调用
            }, 2000);
            return; // 保持loading状态
          } else {
            // 超过最大轮询次数，显示空状态
            setScriptData(null);
            setIsLoading(false);
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
          return; // 保持loading状态
        } else {
          setError('获取剧本失败，请稍后重试');
          setIsLoading(false);
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
  }, [project, getProjectId]);

  // 直接保存用户编辑的大纲
  const handleOutlineSaveDirect = async (content: string) => {
    const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
    if (!projectId) {
      setError('无法获取项目 ID');
      return;
    }
    setIsLoading(true);
    try {
      await updateScriptOutline(projectId, content);
      const result = await getScript(projectId);
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
    const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
    if (!projectId) {
      setError('无法获取项目 ID');
      return;
    }
    setIsLoading(true);
    try {
      await reviseScript(projectId, {
        revisionNote,
        currentOutline: content
      });
      const result = await getScript(projectId);
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

    const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
    if (!projectId) {
      setError('无法获取项目 ID');
      return;
    }
    setIsBatchGenerating(true);
    setIsLoading(true);
    try {
      await generateAllEpisodes(projectId);
      const result = await getScript(projectId);
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
    setIsRegenerateMode(false);
    setDialogOpen(true);
  };

  // 处理重新生成剧集点击
  const handleRegenerateClick = (chapter: string) => {
    setSelectedChapter(chapter);
    setIsRegenerateMode(true);
    setDialogOpen(true);
  };

  // 处理生成剧集确认（支持新生成和重新生成）
  const handleGenerateConfirm = async (episodeCount: number, modificationSuggestion?: string) => {
    if (!selectedChapter) return;

    const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
    if (!projectId) {
      setError('无法获取项目 ID');
      return;
    }

    setIsGenerating(true);
    try {
      if (isRegenerateMode) {
        // 重新生成模式：调用 reviseScript 接口
        await reviseScript(projectId, {
          chapter: selectedChapter,
          episodeCount: String(episodeCount),
          revisionNote: modificationSuggestion || '重新生成'
        });
      } else {
        // 新生成模式：调用 generateEpisodes 接口
        await generateEpisodes(projectId, {
          chapter: selectedChapter,
          episodeCount,
          modificationSuggestion
        });
      }
      // 重新获取剧本数据
      const result = await getScript(projectId);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
      setDialogOpen(false);
    } catch (err) {
      console.error(`${isRegenerateMode ? '重新' : ''}生成剧集失败:`, err);
      setError(`${isRegenerateMode ? '重新' : ''}生成剧集失败，请稍后重试`);
    } finally {
      setIsGenerating(false);
    }
  };

  // 处理确认
  const handleConfirm = async () => {
    console.log('确认项目:', project);
    console.log('当前剧本:', scriptData);

    const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
    if (!projectId) {
      setError('无法获取项目 ID');
      return;
    }

    // 确认前提醒用户
    const confirmed = window.confirm('确认后将进入下一步，无法再返回修改。请确认内容无误后再继续。');
    if (!confirmed) return;

    setIsLoading(true);
    try {
      // 调用确认剧本接口
      await confirmScript(projectId);
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
    // 从 outline 中提取剧名
    const titleMatch = scriptData.outline.match(/^# (.+)$/m);
    const title = titleMatch ? titleMatch[1] : '未命名剧集';
    const genreMatch = scriptData.outline.match(/\*\*类型\*\*:\s*(.+?)\s*\|/);
    const genre = genreMatch ? genreMatch[1] : p.genre || '未分类';
    const episodes = p.totalEpisodes || scriptData.chapters.length || 0;

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
    if (scriptData?.isSingleEpisode || scriptData?.project?.totalEpisodes === 1) {
      return 1;
    }

    const fromChapter = extractEpisodeCountFromChapter(chapterTitle);
    if (fromChapter && fromChapter > 0) {
      return fromChapter;
    }

    const fallback = scriptData?.project?.episodesPerChapter;
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
            onRegenerateClick={handleRegenerateClick}
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
