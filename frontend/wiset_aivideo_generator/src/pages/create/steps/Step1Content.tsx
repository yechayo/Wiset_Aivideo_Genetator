import { useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import styles from '../CreatePage.module.less';
import VoiceSelect from '../../../components/VoiceSelect';
import Select from '../../../components/Select';
import { createProject, updateProject, isApiSuccess, previewVoice } from '../../../services';
import type { CreateProjectRequest, ProductionMode, Project, VisualStyle } from '../../../services';
import type { StepContentProps } from '../types';
import { useProjectStore } from '../../../stores';

const productionModeOptions: { value: ProductionMode; label: string }[] = [
  { value: 'realtime_animation', label: '实时动画漫剧' },
  { value: 'comic_commentary', label: '漫剧解说' },
];

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

// 图片生成商选项
const imageProviderOptions = [
  { value: 'seedream', label: 'Seedream' },
  { value: 'nanobanana', label: 'Nanobanana2' },
];

// 视频生成商选项
const videoProviderOptions = [
  { value: 'vidu', label: 'Vidu' },
  { value: 'grok', label: 'Grok' },
];

// 旁白音色选项
const VOICE_OPTIONS = [
  { value: 'Chinese (Mandarin)_Lyrical_Voice', label: '抒情男声' },
  { value: 'Chinese (Mandarin)_Gentle_Youth', label: '温润青年' },
  { value: 'Chinese (Mandarin)_Sincere_Adult', label: '真诚青年' },
  { value: 'Chinese (Mandarin)_Radio_Host', label: '电台男主播' },
  { value: 'Chinese (Mandarin)_Humorous_Elder', label: '搞笑大爷' },
  { value: 'male-qn-badao', label: '霸道青年' },
  { value: 'male-qn-daxuesheng', label: '青年大学生' },
  { value: 'Chinese (Mandarin)_Gentleman', label: '温润男声' },
  { value: 'Chinese (Mandarin)_Wise_Women', label: '阅历姐姐' },
  { value: 'Chinese (Mandarin)_Warm_Girl', label: '温暖少女' },
  { value: 'Chinese (Mandarin)_Soft_Girl', label: '柔和少女' },
  { value: 'female-shaonv', label: '少女音色' },
  { value: 'female-yujie', label: '御姐音色' },
  { value: 'female-tianmei', label: '甜美女性音色' },
  { value: 'Chinese (Mandarin)_Cute_Spirit', label: '憨憨萌兽' },
  { value: 'Chinese (Mandarin)_Gentle_Senior', label: '温柔学姐' },
];

// 旁白视角选项
const narrationPerspectiveOptions = [
  { value: 'first_person', label: '第一人称（主角旁白）' },
  { value: 'third_person', label: '第三人称（画外音旁白）' },
];

// Vidu 模型选项
const viduModelOptions = [
  { value: 'viduq3-pro', label: 'Vidu Q3 Pro（效果好）' },
  { value: 'viduq3-turbo', label: 'Vidu Q3 Turbo（速度快）' },
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
 * Step 1: 创意输入 + 设定
 *
 * 新建项目：填写故事创意和生成配置，点击创建后跳转 Step 2。
 * 编辑草稿：回填已有的项目配置，点击继续后保存并跳转 Step 2。
 */
const Step1Content = ({ onProjectCreated, project }: Step1ContentProps) => {
  const navigate = useNavigate();
  const setCurrentProject = useProjectStore((state) => state.setCurrentProject);

  // ========== 编辑模式 ==========
  const isEditing = !!project?.projectId;
  const info = project?.projectInfo;

  // ========== 表单状态（编辑时从已有项目回填） ==========
  const [storyIdea, setStoryIdea] = useState(() => info?.storyPrompt || '');
  const [genre, setGenre] = useState(() => info?.genre || '');
  const [visualStyle, setVisualStyle] = useState<VisualStyle | ''>(() => (info?.visualStyle as VisualStyle) || '');
  const [targetAudience, setTargetAudience] = useState(() => info?.targetAudience || '');
  const [totalEpisodes, setTotalEpisodes] = useState(() => info?.totalEpisodes || 10);
  const [episodeDuration, setEpisodeDuration] = useState<number>(() => info?.episodeDuration || 60);
  const [imageProvider, setImageProvider] = useState(() => info?.imageProvider || 'seedream');
  const [videoProvider, setVideoProvider] = useState(() => info?.videoProvider || 'vidu');
  const [videoModel, setVideoModel] = useState(() => info?.videoModel || 'viduq3-pro');
  const [productionMode, setProductionMode] = useState<ProductionMode>(() => (info?.productionMode as ProductionMode) || 'realtime_animation');
  const [narrationPerspective, setNarrationPerspective] = useState<'first_person' | 'third_person' | ''>(() => (info?.narrationPerspective as 'first_person' | 'third_person') || '');
  const [narrationVoiceId, setNarrationVoiceId] = useState(() => info?.narrationVoiceId || '');
  const [protagonistVoiceId, setProtagonistVoiceId] = useState(() => info?.protagonistVoiceId || '');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // ========== 音色试听 ==========
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewVoiceId, setPreviewVoiceId] = useState<string | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);

  // 切换旁白视角时清除已选音色
  const handleNarrationPerspectiveChange = (value: 'first_person' | 'third_person') => {
    setNarrationPerspective(value);
    setNarrationVoiceId('');
    setProtagonistVoiceId('');
  };

  // ========== 音色试听 ==========
  const handlePreviewVoice = async (voiceId: string) => {
    if (previewVoiceId === voiceId && audioRef.current && !audioRef.current.paused) {
      audioRef.current.pause();
      setPreviewVoiceId(null);
      return;
    }
    setPreviewLoading(true);
    setPreviewVoiceId(voiceId);
    try {
      const res = await previewVoice(voiceId);
      if (isApiSuccess(res) && res.data?.audioUrl) {
        if (audioRef.current) {
          audioRef.current.pause();
        }
        audioRef.current = new Audio(res.data.audioUrl);
        audioRef.current.onended = () => setPreviewVoiceId(null);
        audioRef.current.play();
      } else {
        alert('试听生成失败');
        setPreviewVoiceId(null);
      }
    } catch {
      alert('试听请求失败');
      setPreviewVoiceId(null);
    } finally {
      setPreviewLoading(false);
    }
  };

  // ========== 提交处理 ==========
  const handleSubmit = async () => {
    if (!storyIdea.trim() || !visualStyle || !targetAudience) {
      alert('请填写完整的表单信息');
      return;
    }

    const requestData: CreateProjectRequest = {
      storyPrompt: storyIdea,
      genre: genre || undefined,
      visualStyle: visualStyle || undefined,
      targetAudience,
      totalEpisodes,
      episodeDuration: episodeDuration,
      imageProvider,
      videoProvider,
      videoModel,
      productionMode,
      narrationPerspective: narrationPerspective || undefined,
      narrationVoiceId: narrationPerspective === 'third_person' ? narrationVoiceId || undefined : undefined,
      protagonistVoiceId: narrationPerspective === 'first_person' ? protagonistVoiceId || undefined : undefined,
    };

    setIsSubmitting(true);

    try {
      if (isEditing) {
        // 编辑草稿：更新已有项目配置并跳转 Step 2
        await updateProject(project!.projectId!, requestData);
        navigate(`/project/${project!.projectId}/step/2`, { replace: true });
      } else {
        // 新建项目
        const createResult = await createProject(requestData);

        if (isApiSuccess(createResult) && createResult.data) {
          const newProjectId = createResult.data.projectId;

          if (!newProjectId) {
            console.error('创建成功但缺少项目 ID');
            alert('项目创建成功但缺少 ID，请重试');
            return;
          }

          const projectData: Project = { projectId: newProjectId };
          setCurrentProject(projectData);
          onProjectCreated?.(projectData);

          // 创建成功后跳转到 Step 2
          navigate(`/project/${newProjectId}/step/2`, { replace: true });
        } else {
          console.error('创建失败:', createResult.message);
          alert(`创建失败: ${createResult.message}`);
        }
      }
    } catch (error) {
      console.error('API调用失败:', error);
      alert(isEditing ? '更新项目失败，请稍后重试' : 'API调用失败，请稍后重试');
    } finally {
      setIsSubmitting(false);
    }
  };

  // ========== 渲染 ==========
  return (
    <div className={styles.content}>
      {/* 标题区域 */}
      <div className={styles.header}>
        <h1 className={styles.title}>{isEditing ? '编辑项目设置' : '创建新的AI漫剧'}</h1>
        <p className={styles.subtitle}>
          {isEditing
            ? '确认或修改你的创意设定后，继续生成大纲'
            : '输入你的故事创意，AI将自动生成完整的漫剧视频'}
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
            {/* 制作模式 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel}>制作模式</label>
              <Select
                options={productionModeOptions}
                value={productionMode}
                onChange={(v) => setProductionMode(v as ProductionMode)}
              />
            </div>

            {/* 旁白配置 - 仅漫剧解说模式显示 */}
            {productionMode === 'comic_commentary' && (
              <>
                {/* 旁白视角 */}
                <div className={styles.configSection}>
                  <label className={styles.configLabel}>旁白视角</label>
                  <div className={styles.durationGroup}>
                    {narrationPerspectiveOptions.map((option) => (
                      <button
                        key={option.value}
                        type="button"
                        className={`${styles.durationButton} ${narrationPerspective === option.value ? styles.active : ''}`}
                        onClick={() => handleNarrationPerspectiveChange(option.value as 'first_person' | 'third_person')}
                      >
                        {option.label}
                      </button>
                    ))}
                  </div>
                </div>

                {/* 音色选择 - 选中视角后显示 */}
                {narrationPerspective && (
                  <div className={styles.configSection}>
                    <label className={styles.configLabel}>
                      {narrationPerspective === 'first_person' ? '主角音色' : '旁白音色'}
                    </label>
                    <VoiceSelect
                      options={VOICE_OPTIONS}
                      value={narrationPerspective === 'first_person' ? protagonistVoiceId : narrationVoiceId}
                      onChange={(voiceId) => {
                        if (narrationPerspective === 'first_person') {
                          setProtagonistVoiceId(voiceId);
                        } else {
                          setNarrationVoiceId(voiceId);
                        }
                      }}
                      onPreview={handlePreviewVoice}
                      previewLoading={previewLoading}
                      previewVoiceId={previewVoiceId}
                    />
                  </div>
                )}
              </>
            )}

            {/* 题材类型 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel}>题材类型</label>
              <Select
                options={genreOptions}
                value={genre}
                onChange={setGenre}
                placeholder="选择题材"
              />
            </div>

            {/* 画面风格 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel}>画面风格</label>
              <Select
                options={visualStyleOptions}
                value={visualStyle}
                onChange={(v) => setVisualStyle(v as VisualStyle)}
                placeholder="Select Option"
              />
            </div>

            {/* 目标受众 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel}>目标受众</label>
              <Select
                options={targetAudienceOptions}
                value={targetAudience}
                onChange={setTargetAudience}
                placeholder="选择目标受众"
              />
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

            {/* 集数 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel} htmlFor="total-episodes">
                集数
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

            {/* 图片生成商 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel}>图片生成商</label>
              <Select
                options={imageProviderOptions}
                value={imageProvider}
                onChange={setImageProvider}
              />
            </div>

            {/* 视频生成商 */}
            <div className={styles.configSection}>
              <label className={styles.configLabel}>视频生成商</label>
              <Select
                options={videoProviderOptions}
                value={videoProvider}
                onChange={setVideoProvider}
              />
            </div>

            {/* Vidu 模型选择 */}
            {videoProvider === 'vidu' && (
              <div className={styles.configSection}>
                <label className={styles.configLabel}>视频模型</label>
                <Select
                  options={viduModelOptions}
                  value={videoModel}
                  onChange={setVideoModel}
                />
              </div>
            )}
          </div>
        </div>
      </div>

      {/* 提交按钮 */}
      <div className={styles.buttonContainer}>
        <button
          className={styles.generateButton}
          onClick={handleSubmit}
          disabled={!storyIdea.trim() || !visualStyle || !targetAudience || isSubmitting}
        >
          <SparklesIcon className={styles.buttonIcon} />
          <span>{isSubmitting ? (isEditing ? '保存中...' : '创建中...') : (isEditing ? '继续创建' : '创建项目')}</span>
        </button>
      </div>
    </div>
  );
};

export default Step1Content;