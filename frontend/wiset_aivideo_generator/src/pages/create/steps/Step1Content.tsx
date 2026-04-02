import { useState, useEffect, useCallback } from 'react';
import styles from '../CreatePage.module.less';
import styles2 from './Step1Review.module.less';
import { ChevronDownIcon } from '../../../components/icons/Icons';
import { createProject, generateScript, getScript, confirmScript, reviseScript, updateScriptOutline, isApiSuccess } from '../../../services';
import type { CreateProjectRequest, Project, ScriptContentResponse, VisualStyle } from '../../../services';
import type { StepContentProps } from '../types';
import ScriptGeneratingOverlay from '../components/ScriptGeneratingOverlay';
import OutlineEditor from './components/OutlineEditor';
import { useProjectStore } from '../../../stores';
import { useCreateStore } from '../../../stores/createStore';

// 题材类型选项
const genreOptions = [
  { value: '热血玄幻', label: '热血玄幻' },
  { value: '都市异能', label: '都市异能' },
  { value: '科幻机甲', label: '科幻机甲' },
  { value: '悬疑推理', label: '悬疑推理' },
  { value: '都市言情', label: '都市言情' },
  { value: '古风仙侠', label: '古风仙侠' },
  { value: '恐怖灵异', label: '恐怖灵异' },
  { value: '青春校园', label: '青春校园' },
  { value: '搞笑日常', label: '搞笑日常' },
];

// 画面风格选项（与后端 visualStyle 保持一致）
const visualStyleOptions = [
  { value: '3D', label: '3D 写实渲染' },
  { value: 'REAL', label: '照片级写实' },
  { value: 'ANIME', label: '日系2D动漫' },
  { value: 'MANGA', label: '日本漫画' },
  { value: 'INK', label: '中国水墨画' },
  { value: 'CYBERPUNK', label: '赛博朋克' },
];

// 目标受众选项
const targetAudienceOptions = [
  { value: 'children', label: '儿童 (6-12岁)' },
  { value: 'teen', label: '青少年 (13-17岁)' },
  { value: 'young-adult', label: '青年 (18-30岁)' },
  { value: 'adult', label: '成人 (31-50岁)' },
  { value: 'all-ages', label: '全年龄' },
];

// 时长选项（单位：秒）
const durationOptions = [
  { value: 30, label: '30秒' },
  { value: 60, label: '1分钟' },
  { value: 120, label: '2分钟' },
  { value: 180, label: '3分钟' },
  { value: 300, label: '5分钟' },
];

// AI生成图标
function SparklesIcon({ className = '' }: { className?: string }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z" />
      <path d="M5 3v4" />
      <path d="M19 17v4" />
      <path d="M3 5h4" />
      <path d="M17 19h4" />
    </svg>
  );
}

interface Step1ContentProps extends StepContentProps {
  onProjectCreated?: (project: Project) => void;
  project?: Project;
}

/**
 * Step 1: 创意输入 + 大纲审核
 *
 * - 无项目 / draft 状态：展示创意输入表单
 * - outline_review 状态：展示大纲审核 UI（复用 OutlineEditor）
 */
const Step1Content = ({ onProjectCreated, project }: Step1ContentProps) => {
  const { statusInfo } = useCreateStore();
  const projectId = project?.projectId;
  const isOutlineReview = projectId && statusInfo?.statusCode === 'outline_review';

  // ========== 大纲审核状态 ==========
  const [scriptData, setScriptData] = useState<ScriptContentResponse | null>(null);
  const [reviewLoading, setReviewLoading] = useState(false);
  const [confirmLoading, setConfirmLoading] = useState(false);

  const loadScript = useCallback(async (pid: string) => {
    setReviewLoading(true);
    try {
      const result = await getScript(pid);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
    } catch (err) {
      console.error('获取剧本失败:', err);
    } finally {
      setReviewLoading(false);
    }
  }, []);

  // outline_review 时自动加载剧本
  useEffect(() => {
    if (isOutlineReview && projectId) {
      loadScript(projectId);
    }
  }, [isOutlineReview, projectId, loadScript]);

  // ========== 创意输入表单状态 ==========
  const [storyIdea, setStoryIdea] = useState('');
  const [generateMode, setGenerateMode] = useState<'single' | 'series'>('single');
  const [genre, setGenre] = useState('');
  const [visualStyle, setVisualStyle] = useState<VisualStyle | ''>('');
  const [targetAudience, setTargetAudience] = useState('');
  const [totalEpisodes, setTotalEpisodes] = useState(10);
  const [episodeDuration, setEpisodeDuration] = useState<number>(60);
  const [isGenerating, setIsGenerating] = useState(false);
  const [isGeneratingScript, setIsGeneratingScript] = useState(false);
  const [generatingPhase, setGeneratingPhase] = useState<'creating' | 'generating' | 'loading'>('creating');

  // 使用 projectStore 存储项目数据
  const setCurrentProject = useProjectStore((state) => state.setCurrentProject);
  // ========== 大纲审核操作 ==========
  const handleOutlineSaveDirect = async (content: string) => {
    if (!projectId) return;
    setReviewLoading(true);
    try {
      await updateScriptOutline(projectId, content);
      const result = await getScript(projectId);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
    } catch (err) {
      console.error('保存大纲失败:', err);
    } finally {
      setReviewLoading(false);
    }
  };

  const handleOutlineSaveWithAI = async (content: string, revisionNote: string) => {
    if (!projectId) return;
    setReviewLoading(true);
    try {
      await reviseScript(projectId, {
        revisionNote,
        currentOutline: content,
      });
      const result = await getScript(projectId);
      if (isApiSuccess(result) && result.data) {
        setScriptData(result.data);
      }
    } catch (err) {
      console.error('AI 重新生成失败:', err);
    } finally {
      setReviewLoading(false);
    }
  };

  const handleConfirmOutline = async () => {
    if (!projectId) return;
    const confirmed = window.confirm('确认大纲后将进入下一步剧本编辑，无法再返回修改。请确认内容无误后再继续。');
    if (!confirmed) return;
    setConfirmLoading(true);
    try {
      await confirmScript(projectId);
    } catch (err) {
      console.error('确认大纲失败:', err);
      alert('确认大纲失败，请稍后重试');
    } finally {
      setConfirmLoading(false);
    }
  };

  // ========== 创意输入 - 生成处理 ==========
  const handleGenerate = async () => {
    if (!storyIdea.trim() || !visualStyle || !targetAudience) {
      alert('请填写完整的表单信息');
      return;
    }

    setIsGenerating(true);
    setGeneratingPhase('creating');

    try {
      const requestData: CreateProjectRequest = {
        storyPrompt: storyIdea,
        genre: genre || undefined,
        visualStyle: visualStyle || undefined,
        targetAudience,
        totalEpisodes: generateMode === 'series' ? totalEpisodes : 1,
        episodeDuration: episodeDuration,
      };

      const createResult = await createProject(requestData);

      if (isApiSuccess(createResult) && createResult.data) {
        console.log('项目创建成功:', createResult.data);

        const newProjectId = createResult.data.projectId;

        if (!newProjectId) {
          console.error('创建成功但缺少项目 ID');
          alert('项目创建成功但缺少 ID，请重试');
          return;
        }

        const projectData: Project = { projectId: newProjectId };
        setCurrentProject(projectData);
        onProjectCreated?.(projectData);

        setGeneratingPhase('generating');
        setIsGeneratingScript(true);
        try {
          const scriptResult = await generateScript(newProjectId);
          console.log('剧本生成响应:', scriptResult);

          if (isApiSuccess(scriptResult)) {
            console.log('剧本生成成功');
            setGeneratingPhase('loading');
          } else {
            console.warn('剧本生成返回非成功状态:', scriptResult.message);
            setGeneratingPhase('loading');
          }
        } catch (scriptError) {
          console.error('剧本生成失败:', scriptError);
          setGeneratingPhase('loading');
        }
        setIsGeneratingScript(false);

      } else {
        console.error('创建失败:', createResult.message);
        alert(`创建失败: ${createResult.message}`);
      }
    } catch (error) {
      console.error('API调用失败:', error);
      alert('API调用失败，请稍后重试');
    } finally {
      setIsGenerating(false);
    }
  };

  // ========== 大纲审核视图 ==========
  if (isOutlineReview) {
    return (
      <div className={styles.content}>
        <div className={styles.header}>
          <h1 className={styles.title}>大纲审核</h1>
          <p className={styles.subtitle}>审核 AI 生成的剧本大纲，确认后进入剧本编辑</p>
        </div>

        {reviewLoading ? (
          <div className={styles2.loadingState}>
            <div className={styles2.spinner}></div>
            <p>正在加载大纲...</p>
          </div>
        ) : scriptData ? (
          <div className={styles2.reviewContainer}>
            <OutlineEditor
              outline={scriptData.outline}
              onSaveDirect={handleOutlineSaveDirect}
              onSaveWithAI={handleOutlineSaveWithAI}
            />
            <div className={styles2.actionRow}>
              <button
                className={styles2.confirmButton}
                onClick={handleConfirmOutline}
                disabled={confirmLoading}
              >
                {confirmLoading ? '确认中...' : '确认大纲，进入下一步'}
              </button>
            </div>
          </div>
        ) : (
          <div className={styles2.loadingState}>
            <p>暂无大纲数据</p>
          </div>
        )}
      </div>
    );
  }

  // ========== 创意输入视图（原始逻辑） ==========
  return (
    <div className={styles.content}>
      {/* 标题区域 */}
      <div className={styles.header}>
        <h1 className={styles.title}>创建新的AI漫剧</h1>
        <p className={styles.subtitle}>
          输入你的故事创意，AI将自动生成完整的漫剧视频
        </p>
      </div>

      {/* 两栏布局：故事创意 + 生成配置 */}
      <div className={styles.cardsRow}>
        {/* 左栏：故事创意 */}
        <div className={styles.cardStory}>
          <div className={styles.card}>
            <div className={styles.cardHeader}>
              <h2 className={styles.cardTitle}>故事创意</h2>
              <p className={styles.cardSubtitle}>Where dreamworld come alive.</p>
            </div>
            <textarea
              className={styles.textarea}
              placeholder="Write your dreamworld..."
              value={storyIdea}
              onChange={(e) => setStoryIdea(e.target.value)}
              aria-label="故事创意输入框"
            />
          </div>
        </div>

        {/* 右栏：生成配置 */}
        <div className={styles.cardConfig}>
          <div className={styles.card}>
            {/* 生成模式 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel}>生成模式</label>
              <div className={styles.radioGroup}>
                <div
                  className={`${styles.radioItem} ${generateMode === 'single' ? styles.active : ''}`}
                  onClick={() => setGenerateMode('single')}
                  role="radio"
                  aria-checked={generateMode === 'single'}
                  tabIndex={0}
                  onKeyDown={(e) => e.key === 'Enter' && setGenerateMode('single')}
                >
                  <span className={styles.radioButton}>
                    <span className={styles.radioButtonInner}></span>
                  </span>
                  <span>单集视频</span>
                </div>
                <div
                  className={`${styles.radioItem} ${generateMode === 'series' ? styles.active : ''}`}
                  onClick={() => setGenerateMode('series')}
                  role="radio"
                  aria-checked={generateMode === 'series'}
                  tabIndex={0}
                  onKeyDown={(e) => e.key === 'Enter' && setGenerateMode('series')}
                >
                  <span className={styles.radioButton}>
                    <span className={styles.radioButtonInner}></span>
                  </span>
                  <span>系列漫剧</span>
                </div>
              </div>
            </div>

            {/* 题材类型 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel} htmlFor="genre">
                题材类型
              </label>
              <div className={styles.selectWrapper}>
                <select
                  id="genre"
                  className={styles.select}
                  value={genre}
                  onChange={(e) => setGenre(e.target.value)}
                >
                  <option value="" disabled>
                    选择题材
                  </option>
                  {genreOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <ChevronDownIcon className={styles.selectArrow} />
              </div>
            </div>

            {/* 画面风格 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel} htmlFor="visual-style">
                画面风格
              </label>
              <div className={styles.selectWrapper}>
                <select
                  id="visual-style"
                  className={styles.select}
                  value={visualStyle}
                  onChange={(e) => setVisualStyle(e.target.value as VisualStyle)}
                >
                  <option value="" disabled>
                    Select Option
                  </option>
                  {visualStyleOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <ChevronDownIcon className={styles.selectArrow} />
              </div>
            </div>

            {/* 目标受众 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel} htmlFor="target-audience">
                目标受众
              </label>
              <div className={styles.selectWrapper}>
                <select
                  id="target-audience"
                  className={styles.select}
                  value={targetAudience}
                  onChange={(e) => setTargetAudience(e.target.value)}
                >
                  <option value="" disabled>
                    选择目标受众
                  </option>
                  {targetAudienceOptions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
                <ChevronDownIcon className={styles.selectArrow} />
              </div>
            </div>

            {/* 每集时长 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel}>每集时长</label>
              <div className={styles.durationGroup}>
                {durationOptions.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className={`${styles.durationButton} ${episodeDuration === option.value ? styles.active : ''}`}
                    onClick={() => setEpisodeDuration(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>

            {/* 系列漫剧集数 */}
            {generateMode === 'series' && (
              <div className={styles.configSection}>
                <label className={styles.configLabel} htmlFor="total-episodes">
                  总集数
                </label>
                <input
                  id="total-episodes"
                  type="number"
                  className={styles.input}
                  min="1"
                  max="100"
                  value={totalEpisodes}
                  onChange={(e) => setTotalEpisodes(parseInt(e.target.value) || 1)}
                />
              </div>
            )}
          </div>
        </div>
      </div>

      {/* 生成按钮 */}
      <div className={styles.buttonContainer}>
        <button
          className={styles.generateButton}
          onClick={handleGenerate}
          disabled={!storyIdea.trim() || !visualStyle || !targetAudience || isGenerating || isGeneratingScript}
        >
          <SparklesIcon className={styles.buttonIcon} />
          <span>{isGenerating ? '创建中...' : '生成剧本'}</span>
        </button>
      </div>

      {/* 剧本生成加载遮罩 */}
      <ScriptGeneratingOverlay
        isVisible={isGenerating || isGeneratingScript}
        phase={generatingPhase}
      />
    </div>
  );
};

export default Step1Content;
