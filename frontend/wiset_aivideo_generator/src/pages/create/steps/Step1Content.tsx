import { useState } from 'react';
import styles from '../CreatePage.module.less';
import { ChevronDownIcon } from '../../../components/icons/Icons';
import { createProject, generateScript, isApiSuccess } from '../../../services';
import type { CreateProjectRequest, Project, VisualStyle } from '../../../services';
import type { StepContentProps } from '../types';
import ScriptGeneratingOverlay from '../components/ScriptGeneratingOverlay';
import { useProjectStore } from '../../../stores';
import { useTransitionOverlay } from '../CreateLayout';

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
  { value: '30', label: '30秒' },
  { value: '60', label: '1分钟' },
  { value: '120', label: '2分钟' },
  { value: '180', label: '3分钟' },
  { value: '300', label: '5分钟' },
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
  onProjectCreated: (project: Project) => void;
}

/**
 * Step 1: 创意输入
 */
const Step1Content = ({ onProjectCreated }: Step1ContentProps) => {
  // 表单状态
  const [storyIdea, setStoryIdea] = useState('');
  const [generateMode, setGenerateMode] = useState<'single' | 'series'>('single');
  const [genre, setGenre] = useState('');
  const [visualStyle, setVisualStyle] = useState<VisualStyle | ''>('');
  const [targetAudience, setTargetAudience] = useState('');
  const [totalEpisodes, setTotalEpisodes] = useState(10);
  const [episodeDuration, setEpisodeDuration] = useState('60');
  const [isGenerating, setIsGenerating] = useState(false);
  const [isGeneratingScript, setIsGeneratingScript] = useState(false);
  const [generatingPhase, setGeneratingPhase] = useState<'creating' | 'generating' | 'loading'>('creating');

  // 使用 projectStore 存储项目数据
  const setCurrentProject = useProjectStore((state) => state.setCurrentProject);
  // 过渡遮罩 context：生成完成后保持遮罩到 Step2 mount
  const { showTransitionOverlay } = useTransitionOverlay();

  // 处理生成
  const handleGenerate = async () => {
    if (!storyIdea.trim() || !visualStyle || !targetAudience) {
      alert('请填写完整的表单信息');
      return;
    }

    setIsGenerating(true);
    setGeneratingPhase('creating');

    try {
      // 映射表单字段到 API 字段
      const requestData: CreateProjectRequest = {
        storyPrompt: storyIdea,
        genre: genre || undefined,
        visualStyle: visualStyle || undefined,
        targetAudience,
        totalEpisodes: generateMode === 'series' ? totalEpisodes : 1,
        episodeDuration: parseFloat(episodeDuration),
      };

      // 1. 创建项目
      const createResult = await createProject(requestData);

      if (isApiSuccess(createResult) && createResult.data) {
        console.log('项目创建成功:', createResult.data);

        const projectId = createResult.data.projectId;

        // 验证 projectId 存在
        if (!projectId) {
          console.error('创建成功但缺少项目 ID');
          alert('项目创建成功但缺少 ID，请重试');
          return;
        }

        // 构造 Project 对象存入 store（后端只返回 projectId）
        const projectData: Project = { projectId };
        setCurrentProject(projectData);
        onProjectCreated(projectData);

        // 2. 自动触发剧本生成
        console.log('开始生成剧本，项目 ID:', projectId);

        setGeneratingPhase('generating');
        setIsGeneratingScript(true);
        try {
          const scriptResult = await generateScript(projectId);
          console.log('剧本生成响应:', scriptResult);

          if (isApiSuccess(scriptResult)) {
            console.log('剧本生成成功');
            // 切换到加载阶段，保持遮罩直到 Step2 接管
            setGeneratingPhase('loading');
          } else {
            console.warn('剧本生成返回非成功状态:', scriptResult.message);
            setGeneratingPhase('loading');
          }
        } catch (scriptError) {
          console.error('剧本生成失败:', scriptError);
          // 即使剧本生成失败，也继续流程
          setGeneratingPhase('loading');
        }
        // 通知 CreateLayout 显示全局过渡遮罩，覆盖 Step1→Step2 的空白期
        showTransitionOverlay();
        setIsGeneratingScript(false);
        // Step2 mount 后会调用 hideTransitionOverlay 接管

        // 不在这里主动跳转，等待状态轮询/SSE驱动步骤切换
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

  return (
    <div className={styles.content}>
      {/* 标题区域 */}
      <div className={styles.header}>
        <h1 className={styles.title}>创建新的AI漫剧</h1>
        <p className={styles.subtitle}>
          输入你的故事创意，AI将自动生成完整的漫剧视频
        </p>
      </div>

      {/* 故事创意卡片 */}
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

      {/* 生成配置卡片 */}
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
