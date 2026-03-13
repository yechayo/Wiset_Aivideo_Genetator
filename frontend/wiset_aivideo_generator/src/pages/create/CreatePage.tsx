import { useState } from 'react';
import styles from './CreatePage.module.less';
import { ChevronDownIcon } from '../../components/icons/Icons';

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

// 步骤配置
const steps = [
  { id: 1, label: '创意输入' },
  { id: 2, label: '生成配置' },
];

// 画面风格选项
const visualStyleOptions = [
  { value: '2d-anime', label: '2D 动漫' },
  { value: '3d-realistic', label: '3D 写实' },
  { value: 'ink-chinese', label: '水墨国风' },
  { value: 'cyberpunk', label: '赛博朋克' },
  { value: 'japanese-manga', label: '日系漫画' },
];

// 时长选项
const durationOptions = [
  { value: '15-30s', label: '15-30秒' },
  { value: '30-60s', label: '30-60秒' },
  { value: '1-2min', label: '1-2分钟' },
];

const CreatePage = () => {
  // 表单状态
  const [storyIdea, setStoryIdea] = useState('');
  const [generateMode, setGenerateMode] = useState<'single' | 'series'>('single');
  const [visualStyle, setVisualStyle] = useState('');
  const [duration, setDuration] = useState('30-60s');
  const [isGenerating, setIsGenerating] = useState(false);

  // 当前步骤（基于表单填写状态）
  const currentStep = storyIdea.trim() ? 2 : 1;

  // 处理生成
  const handleGenerate = async () => {
    if (!storyIdea.trim()) {
      return;
    }

    setIsGenerating(true);

    // TODO: 调用API生成剧本
    const formData = {
      storyIdea,
      generateMode,
      visualStyle,
      duration,
    };

    console.log('生成表单数据:', formData);

    // 模拟API调用
    setTimeout(() => {
      setIsGenerating(false);
      // TODO: 跳转到生成结果页面
    }, 2000);
  };

  return (
    <div className={styles.createContainer}>
      {/* Step 指示器 */}
      <div className={styles.stepIndicator}>
        <div className={styles.stepContainer}>
          <div className={styles.stepList}>
            {steps.map((step, index) => (
              <div key={step.id} className={styles.stepItemGroup}>
                <div
                  className={`${styles.stepItem} ${currentStep >= step.id ? styles.active : ''}`}
                >
                  <span className={styles.stepNumber}>{step.id}</span>
                  <span>{step.label}</span>
                </div>
                {index < steps.length - 1 && (
                  <span className={styles.stepArrow}>→</span>
                )}
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* 主内容区域 */}
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
          <div className={styles.cardHeader}>
            <h2 className={styles.cardTitle}>生成配置</h2>
            <p className={styles.cardSubtitle}>Configure your AI generation</p>
          </div>

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
                onChange={(e) => setVisualStyle(e.target.value)}
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

          {/* 每集时长 */}
          <div className={styles.configSection}>
            <label className={styles.configLabel}>每集时长</label>
            <div className={styles.durationGroup}>
              {durationOptions.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className={`${styles.durationButton} ${duration === option.value ? styles.active : ''}`}
                  onClick={() => setDuration(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* 生成按钮 */}
        <div className={styles.buttonContainer}>
          <button
            className={styles.generateButton}
            onClick={handleGenerate}
            disabled={!storyIdea.trim() || isGenerating}
          >
            <SparklesIcon className={styles.buttonIcon} />
            <span>{isGenerating ? '生成中...' : '生成剧本'}</span>
          </button>
        </div>
      </div>
    </div>
  );
};

export default CreatePage;
