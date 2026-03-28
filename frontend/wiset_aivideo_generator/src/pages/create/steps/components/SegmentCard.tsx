import React, { useState } from 'react';
import type { SegmentState, SegmentPipelineStep } from '../types';
import styles from './SegmentCard.module.less';
import { ComicPanel } from './ComicPanel';
import VideoPanel from './VideoPanel';

export interface SegmentCardProps {
  episodeId: number;
  segment: SegmentState;
  sceneSummary?: string;
  isExpanded: boolean;
  onToggle: () => void;
  onApprove: () => void;
  onRegenerate: (feedback: string) => void;
  onGenerateVideo: () => void;
  onGenerateComic?: () => void;
  isGeneratingComic?: boolean;
  isGeneratingVideo?: boolean;
  onReviseSinglePanel?: (feedback: string) => void;
  isRevisingPanel?: boolean;
  onUpdatePanel?: (fields: Record<string, any>) => void;
  isUpdatingPanel?: boolean;
  onGenerateBackground?: (panelId: string) => void;
  isGeneratingBackground?: boolean;
}

/**
 * 获取流水线步骤状态
 */
const getPipelineStepStatus = (step: SegmentPipelineStep): {
  scene: 'completed' | 'active' | 'pending';
  comic: 'completed' | 'active' | 'pending';
  video: 'completed' | 'active' | 'pending';
} => {
  switch (step) {
    case 'pending':
      return { scene: 'pending', comic: 'pending', video: 'pending' };
    case 'scene_ready':
      return { scene: 'completed', comic: 'active', video: 'pending' };
    case 'comic_review':
      return { scene: 'completed', comic: 'active', video: 'pending' };
    case 'comic_approved':
      return { scene: 'completed', comic: 'completed', video: 'active' };
    case 'video_generating':
      return { scene: 'completed', comic: 'completed', video: 'active' };
    case 'video_completed':
      return { scene: 'completed', comic: 'completed', video: 'completed' };
    case 'video_failed':
      return { scene: 'completed', comic: 'completed', video: 'active' };
    default:
      return { scene: 'pending', comic: 'pending', video: 'pending' };
  }
};

/**
 * SegmentCard 组件
 * - 折叠状态：单行显示（状态图标 + 片段编号 + 摘要 + 场景 + 角色 + 进度指示器）
 * - 展开状态：Header + 左右分栏内容区（ComicPanel + VideoPanel）
 */
export const SegmentCard: React.FC<SegmentCardProps> = ({
  episodeId: _episodeId, // eslint-disable-line @typescript-eslint/no-unused-vars
  segment,
  sceneSummary,
  isExpanded,
  onToggle,
  onApprove,
  onRegenerate,
  onGenerateVideo,
  onGenerateComic,
  isGeneratingComic,
  onReviseSinglePanel,
  isRevisingPanel,
  onUpdatePanel,
  isUpdatingPanel,
  onGenerateBackground,
  isGeneratingBackground,
}) => {
  const stepStatus = getPipelineStepStatus(segment.pipelineStep);
  const [showManualEdit, setShowManualEdit] = useState(false);
  const [showPromptDetail, setShowPromptDetail] = useState(false);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set(['plot', 'fields', 'comic', 'video']));
  const toggleGroup = (key: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };
  const [durationValue, setDurationValue] = useState(segment.panelData?.duration || 5);
  const [revisionFeedback, setRevisionFeedback] = useState('');
  const [editFields, setEditFields] = useState({
    composition: segment.panelData?.dialogue || '',
    dialogue: Array.isArray(segment.panelData?.dialogue)
      ? segment.panelData.dialogue.map((d: any) => d.speaker ? `${d.speaker}：${d.text}` : d.text).join('\n')
      : '',
    image_prompt_hint: segment.panelData?.imagePromptHint || '',
  });

  return (
    <div className={`${styles.segmentCard} ${isExpanded ? styles.expanded : ''}`}>
      {/* Header - 折叠/展开都显示 */}
      <div className={styles.header} onClick={onToggle}>
        {/* 顶部行：左侧 + 右侧并排，必要时换行 */}
        <div className={styles.headerTopRow}>
          {/* 左侧：状态图标 + 片段编号 + 摘要 */}
          <div className={styles.headerLeft}>
            {/* 状态图标 */}
            <div className={styles.statusIcon}>
              {segment.pipelineStep === 'video_completed' && (
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                  <circle cx="8" cy="8" r="7" stroke="currentColor" strokeWidth="1.5" />
                  <path
                    d="M5 8L7 10L11 6"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              )}
              {segment.pipelineStep === 'video_failed' && (
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                  <circle cx="8" cy="8" r="7" stroke="currentColor" strokeWidth="1.5" />
                  <path
                    d="M5.5 5.5L10.5 10.5M10.5 5.5L5.5 10.5"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                  />
                </svg>
              )}
              {segment.pipelineStep === 'video_generating' && (
                <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                  <circle cx="8" cy="8" r="7" stroke="currentColor" strokeWidth="1.5" strokeOpacity="0.3" />
                  <path
                    d="M8 1V3M8 13V15M15 8H13M3 8H1M12.95 12.95L11.54 11.54M4.46 4.46L3.05 3.05M12.95 3.05L11.54 4.46M4.46 11.54L3.05 12.95"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                  />
                </svg>
              )}
            </div>

            {/* 片段编号 */}
            <span className={styles.segmentIndex}>#{segment.segmentIndex + 1}</span>

            {/* 摘要 */}
            <span className={styles.synopsis}>{segment.synopsis}</span>
          </div>

          {/* 右侧：场景缩略图 + 角色头像 + 进度指示器 + 展开箭头 */}
          <div className={styles.headerRight}>
          {/* 场景缩略图 */}
          {segment.sceneThumbnail ? (
            <div className={styles.sceneThumbnail}>
              <img src={segment.sceneThumbnail} alt="" />
            </div>
          ) : (
            <div
              className={`${styles.sceneIcon} ${onGenerateBackground ? styles.clickable : ''}`}
              onClick={onGenerateBackground ? (e) => {
                e.stopPropagation();
                onGenerateBackground(segment.panelData?.panelId || '');
              } : undefined}
              title={onGenerateBackground ? '生成背景图' : undefined}
            >
              {isGeneratingBackground ? (
                <span className={styles.generatingText}><span className={styles.miniSpinner} />生成中...</span>
              ) : (
                <>
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                    <rect x="1" y="3" width="14" height="10" rx="2" stroke="currentColor" strokeWidth="1.5" />
                    <circle cx="5" cy="7" r="1.5" fill="currentColor" />
                    <path d="M2 12L4.5 9L7 11.5L10 8L14 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                  <span className={styles.scenePlaceholderText}>点击生成背景</span>
                  <span className={styles.clickIcon}>👆</span>
                </>
              )}
            </div>
          )}

          {/* 角色头像列表 */}
          {segment.characterAvatars.length > 0 && (
            <div className={styles.characterAvatars}>
              {segment.characterAvatars.slice(0, 3).map((char, idx) => (
                <div
                  key={idx}
                  className={styles.avatar}
                  style={{ zIndex: 10 - idx }}
                  title={char.name}
                >
                  {char.avatarUrl ? (
                    <img src={char.avatarUrl} alt={char.name} />
                  ) : (
                    char.name.charAt(0)
                  )}
                </div>
              ))}
              {segment.characterAvatars.length > 3 && (
                <div className={`${styles.avatar} ${styles.avatarMore}`}>
                  +{segment.characterAvatars.length - 3}
                </div>
              )}
            </div>
          )}

          {/* 三步进度指示器 */}
          <div className={styles.progressIndicator}>
            {/* 场景步骤 */}
            <div
              className={`${styles.stepDot} ${
                stepStatus.scene === 'completed'
                  ? styles.completed
                  : stepStatus.scene === 'active'
                  ? styles.active
                  : styles.pending
              }`}
              title="场景"
            >
              <svg width="10" height="10" viewBox="0 0 16 16" fill="none">
                <path
                  d="M8 1L10 6L15 6L11 10L12 15L8 12L4 15L5 10L1 6L6 6L8 1Z"
                  fill="currentColor"
                />
              </svg>
            </div>

            {/* 连接线 */}
            <div
              className={`${styles.stepLine} ${
                stepStatus.scene === 'completed' ? styles.completed : styles.pending
              }`}
            />

            {/* 四宫格步骤 */}
            <div
              className={`${styles.stepDot} ${
                stepStatus.comic === 'completed'
                  ? styles.completed
                  : stepStatus.comic === 'active'
                  ? styles.active
                  : styles.pending
              }`}
              title="四宫格"
            >
              <svg width="10" height="10" viewBox="0 0 16 16" fill="none">
                <rect x="1" y="1" width="6" height="6" rx="1" fill="currentColor" />
                <rect x="9" y="1" width="6" height="6" rx="1" fill="currentColor" />
                <rect x="1" y="9" width="6" height="6" rx="1" fill="currentColor" />
                <rect x="9" y="9" width="6" height="6" rx="1" fill="currentColor" />
              </svg>
            </div>

            {/* 连接线 */}
            <div
              className={`${styles.stepLine} ${
                stepStatus.comic === 'completed' ? styles.completed : styles.pending
              }`}
            />

            {/* 视频步骤 */}
            <div
              className={`${styles.stepDot} ${
                stepStatus.video === 'completed'
                  ? styles.completed
                  : stepStatus.video === 'active'
                  ? styles.active
                  : styles.pending
              }`}
              title="视频"
            >
              <svg width="10" height="10" viewBox="0 0 16 16" fill="none">
                <path
                  d="M2 4C2 3.44772 2.44772 3 3 3H13C13.5523 3 14 3.44772 14 4V12C14 12.5523 13.5523 13 13 13H3C2.44772 13 2 12.5523 2 12V4Z"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                />
                <path
                  d="M7 6.5V9.5L10 8L7 6.5Z"
                  fill="currentColor"
                />
              </svg>
            </div>
          </div>

          {/* 展开箭头 */}
          <div className={`${styles.expandArrow} ${isExpanded ? styles.expanded : ''}`}>
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <path
                d="M4 6L8 10L12 6"
                stroke="currentColor"
                strokeWidth="1.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </div>
        </div>
        </div>
      </div>

      {/* 展开内容区 */}
      {isExpanded && (
        <div className={styles.content}>
          {/* 左侧：四宫格漫画面板 */}
          <div className={styles.panel}>
            <ComicPanel
              comicUrl={segment.comicUrl}
              pipelineStep={segment.pipelineStep}
              onApprove={onApprove}
              onRegenerate={onRegenerate}
              onGenerateComic={onGenerateComic}
              isGeneratingComic={isGeneratingComic}
            />
          </div>

          {/* 右侧：AI 视频面板 */}
          <div className={styles.panel}>
            <VideoPanel
              videoUrl={segment.videoUrl}
              pipelineStep={segment.pipelineStep}
              onGenerateVideo={onGenerateVideo}
              isGenerating={isGeneratingVideo}
              videoTaskId={segment.videoTaskId}
              videoOffPeak={segment.videoOffPeak}
            />
          </div>

          {/* 提示词详情 */}
          {segment.panelData && (
            <div className={styles.promptDetailSection}>
              <div
                className={styles.promptDetailToggle}
                onClick={() => setShowPromptDetail(v => !v)}
              >
                <span>生成提示词</span>
                <span className={`${styles.promptDetailArrow} ${showPromptDetail ? styles.expanded : ''}`}>▶</span>
              </div>
              {showPromptDetail && (() => {
                const d = segment.panelData;
                // 拼接四宫格提示词（与后端 buildComicPrompt 逻辑一致）
                const comicParts: string[] = [];
                if (d.background?.scene_desc) comicParts.push(`场景：${d.background.scene_desc}。`);
                if (d.composition) comicParts.push(`构图：${d.composition}。`);
                const shotMap: Record<string, string> = { WIDE_SHOT: '远景镜头。', MID_SHOT: '中景镜头。', CLOSE_UP: '特写镜头。', OVER_SHOULDER: '过肩镜头。' };
                if (d.shotType && shotMap[d.shotType]) comicParts.push(shotMap[d.shotType]);
                const angleMap: Record<string, string> = { eye_level: '平视角度。', low_angle: '低角度仰拍。', high_angle: '高角度俯拍。', bird_eye: '鸟瞰视角。' };
                if (d.cameraAngle && angleMap[d.cameraAngle]) comicParts.push(angleMap[d.cameraAngle]);
                if (d.characters?.length > 0) {
                  comicParts.push('画面中的角色：');
                  d.characters.forEach((c: any) => comicParts.push(`${c.name || c.char_id}，${c.pose || ''}${c.expression ? '，' + c.expression + '表情' : ''}${c.position ? '，位置：' + c.position : ''}；`));
                }
                if (d.dialogue) {
                  comicParts.push(`台词：${d.dialogue}；`);
                }
                if (d.imagePromptHint) comicParts.push(`画面细节参考：${d.imagePromptHint}`);
                comicParts.push('保持参考图的背景风格和场景布局不变。生成2行2列四宫格漫画，共4个连续分镜画面，每个格子标注序号(1,2,3,4)。高质量动漫风格，画面精细。');
                const comicPrompt = comicParts.join('');

                // 拼接视频提示词（与后端 buildVideoPrompt 逻辑一致）
                const videoParts: string[] = [];
                videoParts.push('你是一个专业的视频导演，请根据以下分镜描述生成高质量的视频。');
                if (d.composition) videoParts.push(`【构图描述】${d.composition}。`);
                if (d.background?.scene_desc) videoParts.push(`【场景描述】${d.background.scene_desc}。`);
                if (d.background?.atmosphere) videoParts.push(`【氛围】${d.background.atmosphere}。`);
                if (d.background?.time_of_day) videoParts.push(`【时间】${d.background.time_of_day}。`);
                const videoShotMap: Record<string, string> = { WIDE_SHOT: '【镜头类型】远景，展示完整场景。', MID_SHOT: '【镜头类型】中景，聚焦角色半身。', CLOSE_UP: '【镜头类型】特写，聚焦面部细节。', OVER_SHOULDER: '【镜头类型】过肩镜头。' };
                if (d.shotType && videoShotMap[d.shotType]) videoParts.push(videoShotMap[d.shotType]);
                const videoAngleMap: Record<string, string> = { eye_level: '【镜头角度】平视角度。', low_angle: '【镜头角度】低角度仰拍。', high_angle: '【镜头角度】高角度俯拍。', bird_eye: '【镜头角度】鸟瞰俯视视角。' };
                if (d.cameraAngle && videoAngleMap[d.cameraAngle]) videoParts.push(videoAngleMap[d.cameraAngle]);
                const pacingMap: Record<string, string> = { slow: '【运动节奏】缓慢、从容的运动节奏。', fast: '【运动节奏】快速、充满动感的运动节奏。' };
                if (d.pacing) videoParts.push(pacingMap[d.pacing] || '【运动节奏】自然平稳的运动节奏。');
                if (d.characters?.length > 0) {
                  videoParts.push('【角色表演】');
                  d.characters.forEach((c: any) => {
                    let part = '';
                    if (c.pose) part += c.pose;
                    if (c.expression) part += `，${c.expression}表情`;
                    if (c.position) part += `，位置：${c.position}`;
                    videoParts.push(part + '；');
                  });
                }
                if (d.dialogue) videoParts.push(`【对话台词】${d.dialogue}；`);
                if (d.sfx?.length > 0) videoParts.push(`【音效设计】${d.sfx.join('、')}。`);
                if (d.imagePromptHint) videoParts.push(`【画面细节参考】${d.imagePromptHint}`);
                videoParts.push('[风格前缀]');
                videoParts.push('流畅的动画效果，自然的镜头运动。');
                const videoPrompt = videoParts.join('');

                return (
                  <div className={styles.promptDetailContent}>
                    {sceneSummary && (
                      <div className={styles.promptGroup}>
                        <div className={styles.promptGroupTitle} onClick={() => toggleGroup('plot')}>
                          <span className={styles.promptGroupArrow}>{expandedGroups.has('plot') ? '▼' : '▶'}</span>
                          剧情摘要
                        </div>
                        {expandedGroups.has('plot') && <div className={styles.promptGroupBody}>{sceneSummary}</div>}
                      </div>
                    )}
                    {/* 原始字段 */}
                    <div className={styles.promptGroup}>
                      <div className={styles.promptGroupTitle} onClick={() => toggleGroup('fields')}>
                        <span className={styles.promptGroupArrow}>{expandedGroups.has('fields') ? '▼' : '▶'}</span>
                        分镜字段
                      </div>
                      {expandedGroups.has('fields') && (
                      <div className={styles.promptFieldGrid}>
                        {d.composition && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>构图描述</span><span className={styles.pfValue}>{d.composition}</span></div>}
                        {(d.shotType || d.cameraAngle) && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>镜头</span><span className={styles.pfValue}>{d.shotType}{d.cameraAngle ? ` / ${d.cameraAngle}` : ''}</span></div>}
                        {d.pacing && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>节奏</span><span className={styles.pfValue}>{d.pacing}</span></div>}
                        <div className={styles.promptFieldItem}><span className={styles.pfLabel}>时长</span><span className={styles.pfValue}><input type="number" value={durationValue} min={1} max={16} onChange={e => setDurationValue(Number(e.target.value))} onBlur={() => { const v = Math.max(1, Math.min(16, Math.round(durationValue || 5))); setDurationValue(v); if (v !== d.duration && onUpdatePanel) { onUpdatePanel({ duration: v }); } }} style={{ width: 48, padding: '2px 6px', border: '1px solid #d9d9d9', borderRadius: 4, textAlign: 'center', fontSize: 13, outline: 'none' }} />s</span></div>
                        {d.background?.scene_desc && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>场景描述</span><span className={styles.pfValue}>{d.background.scene_desc}</span></div>}
                        {d.background?.atmosphere && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>氛围</span><span className={styles.pfValue}>{d.background.atmosphere}</span></div>}
                        {d.background?.time_of_day && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>时间</span><span className={styles.pfValue}>{d.background.time_of_day}</span></div>}
                        {d.characters?.length > 0 && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>角色</span><span className={styles.pfValue}>{d.characters.map((c: any) => `${c.name || c.char_id}${c.expression ? `(${c.expression})` : ''}${c.pose ? `[${c.pose}]` : ''}`).join('、')}</span></div>}
                        {d.dialogue && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>对话</span><span className={styles.pfValue}>{d.dialogue}</span></div>}
                        {d.sfx?.length > 0 && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>音效</span><span className={styles.pfValue}>{d.sfx.join('、')}</span></div>}
                        {d.imagePromptHint && <div className={`${styles.promptFieldItem} ${styles.promptFieldItemFull}`}><span className={styles.pfLabel}>画面提示词</span><span className={styles.pfValue}>{d.imagePromptHint}</span></div>}
                      </div>
                      )}
                    </div>
                    {/* 图片生成提示词 */}
                    <div className={styles.promptGroup}>
                      <div className={styles.promptGroupTitle} onClick={() => toggleGroup('comic')}>
                        <span className={styles.promptGroupArrow}>{expandedGroups.has('comic') ? '▼' : '▶'}</span>
                        <span className={styles.promptTagImage}>图片</span> 四宫格生成提示词
                      </div>
                      {expandedGroups.has('comic') && <div className={styles.promptGroupBody}>{comicPrompt}</div>}
                    </div>
                    {/* 视频生成提示词 */}
                    <div className={styles.promptGroup}>
                      <div className={styles.promptGroupTitle} onClick={() => toggleGroup('video')}>
                        <span className={styles.promptGroupArrow}>{expandedGroups.has('video') ? '▼' : '▶'}</span>
                        <span className={styles.promptTagVideo}>视频</span> 视频生成提示词
                      </div>
                      {expandedGroups.has('video') && <div className={styles.promptGroupBody}>{videoPrompt}</div>}
                    </div>
                  </div>
                );
              })()}
            </div>
          )}

          {/* 分镜修改区域 - 四宫格已生成后不再需要 */}
          {!segment.comicUrl && (
          <div className={styles.revisionSection}>
            <div className={styles.revisionHeader}>
              <span className={styles.revisionTitle}>分镜修改</span>
              <div className={styles.revisionTabs}>
                <button
                  className={`${styles.revisionTab} ${!showManualEdit ? styles.activeTab : ''}`}
                  onClick={() => setShowManualEdit(false)}
                >
                  AI修改
                </button>
                <button
                  className={`${styles.revisionTab} ${showManualEdit ? styles.activeTab : ''}`}
                  onClick={() => setShowManualEdit(true)}
                >
                  手动编辑
                </button>
              </div>
            </div>

            {!showManualEdit ? (
              <div className={styles.revisionContent}>
                <textarea
                  className={styles.revisionTextarea}
                  placeholder="请输入修改建议，例如：把角色表情改为愤怒，增加更多对话..."
                  value={revisionFeedback}
                  onChange={(e) => setRevisionFeedback(e.target.value)}
                  disabled={isRevisingPanel}
                  rows={3}
                />
                <button
                  className={styles.revisionButton}
                  onClick={() => {
                    if (revisionFeedback.trim() && onReviseSinglePanel) {
                      onReviseSinglePanel(revisionFeedback.trim());
                      setRevisionFeedback('');
                    }
                  }}
                  disabled={isRevisingPanel || !revisionFeedback.trim()}
                >
                  {isRevisingPanel ? '修改中...' : '提交修改'}
                </button>
              </div>
            ) : (
              <div className={styles.revisionContent}>
                <label className={styles.editLabel}>
                  构图描述
                  <textarea
                    className={styles.editTextarea}
                    value={editFields.composition || ''}
                    onChange={(e) => setEditFields(f => ({ ...f, composition: e.target.value }))}
                    disabled={isUpdatingPanel}
                    rows={3}
                  />
                </label>
                <label className={styles.editLabel}>
                  对话内容
                  <textarea
                    className={styles.editTextarea}
                    value={editFields.dialogue || ''}
                    onChange={(e) => setEditFields(f => ({ ...f, dialogue: e.target.value }))}
                    disabled={isUpdatingPanel}
                    rows={3}
                  />
                </label>
                <label className={styles.editLabel}>
                  画面提示词
                  <textarea
                    className={styles.editTextarea}
                    value={editFields.image_prompt_hint || ''}
                    onChange={(e) => setEditFields(f => ({ ...f, image_prompt_hint: e.target.value }))}
                    disabled={isUpdatingPanel}
                    rows={3}
                  />
                </label>
                <button
                  className={styles.revisionButton}
                  onClick={() => {
                    if (onUpdatePanel) {
                      onUpdatePanel(editFields);
                    }
                  }}
                  disabled={isUpdatingPanel}
                >
                  {isUpdatingPanel ? '保存中...' : '保存修改'}
                </button>
              </div>
            )}
          </div>
          )}
        </div>
      )}
    </div>
  );
};
