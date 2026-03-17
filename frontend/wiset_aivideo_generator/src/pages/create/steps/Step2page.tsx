import { useEffect, useState } from 'react';
import styles from './Step2page.module.less';
import type { Project, ScriptContentResponse } from '../../../services';
import { getScript, generateEpisodes, confirmScript, reviseScript, isApiSuccess } from '../../../services';
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
const Step2page = ({ project, onComplete, onBack }: Step2pageProps) => {
  const [scriptData, setScriptData] = useState<ScriptContentResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 生成剧集对话框状态
  const [dialogOpen, setDialogOpen] = useState(false);
  const [selectedChapter, setSelectedChapter] = useState<string | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [isRegenerateMode, setIsRegenerateMode] = useState(false); // 是否为重新生成模式

  const getProjectId = useProjectStore((state) => state.getProjectId);

  // 判断是否为单集模式
  const isSingleEpisode = scriptData?.project?.totalEpisodes === 1;

  useEffect(() => {
    const fetchScript = async () => {
      // 获取项目 ID（优先使用 store 中的，回退到 props 中的）
      const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);

      if (!projectId) {
        setError('无法获取项目 ID');
        setIsLoading(false);
        return;
      }

      setIsLoading(true);
      setError(null);

      try {
        const result = await getScript(projectId);
        console.log('剧本数据:', result);

        if (isApiSuccess(result) && result.data) {
          setScriptData(result.data);
        } else {
          // 剧本可能还没有生成完成，使用空数据
          setScriptData(null);
          console.warn('剧本数据为空或未生成');
        }
      } catch (err) {
        console.error('获取剧本失败:', err);
        setError('获取剧本失败，请稍后重试');
      } finally {
        setIsLoading(false);
      }
    };

    fetchScript();
  }, [project, getProjectId]);

  // 处理大纲保存（修改大纲会删除所有已生成的剧集）
  const handleOutlineSave = async (_content: string, revisionNote: string) => {
    const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);
    if (!projectId) {
      setError('无法获取项目 ID');
      return;
    }

    setIsLoading(true);
    try {
      // 调用修改大纲接口（不传 chapter 参数即为修改大纲）
      await reviseScript(projectId, { revisionNote });
      // 重新获取剧本数据
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

    // 多集模式下检查是否所有章节都已生成
    if (!isSingleEpisode && scriptData && scriptData.pendingChapters.length > 0) {
      const confirmed = window.confirm(`还有 ${scriptData.pendingChapters.length} 个章节未生成剧集，确定要继续吗？`);
      if (!confirmed) return;
    }

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
      onComplete();
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

  const projectInfo = scriptData ? getProjectInfo() : null;

  return (
    <div className={styles.content}>
      {/* 标题区域 */}
      <div className={styles.header}>
        <h1 className={styles.title}>剧本编辑</h1>
        <p className={styles.subtitle}>
          {isSingleEpisode
            ? '编辑 AI 生成的剧本大纲'
            : '编辑 AI 生成的剧本大纲，并生成各章节剧集'
          }
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
            onSave={handleOutlineSave}
          />

          {/* 章节列表（仅多集模式显示） */}
          {!isSingleEpisode && (
            <ChapterList
              chapters={scriptData.chapters}
              generatedChapters={scriptData.generatedChapters}
              pendingChapters={scriptData.pendingChapters}
              episodes={scriptData.episodes}
              onGenerateClick={handleGenerateClick}
              onRegenerateClick={handleRegenerateClick}
            />
          )}
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
        onClose={() => setDialogOpen(false)}
        onConfirm={handleGenerateConfirm}
        loading={isGenerating}
      />

      {/* 按钮容器 */}
      <div className={styles.buttonContainer}>
        <button
          className={styles.confirmButton}
          onClick={handleConfirm}
          disabled={isLoading}
        >
          下一步
        </button>
      </div>
    </div>
  );
};

export default Step2page;
