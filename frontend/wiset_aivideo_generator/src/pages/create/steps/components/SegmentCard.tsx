import React, { useState } from 'react';
import type { SegmentState, SegmentPipelineStep } from '../types';
import styles from './SegmentCard.module.less';
import { GridReviewPanel } from './GridReviewPanel';
import { PanelGroupView } from './PanelGroupView';
import VideoPanel from './VideoPanel';

export interface SegmentCardProps {
  episodeId: number;
  segment: SegmentState;
  sceneSummary?: string;
  isExpanded: boolean;
  onToggle: () => void;
  onApproveGrid: () => void;
  onRejectGrid: (reason: string) => void;
  onRegenerateGrid: (customHint?: string) => void;
  onGenerateVideo: (customPrompt?: string) => void;
  onLoadVideoPrompt?: () => Promise<string>;
  onEnhanceVideoPrompt?: () => Promise<string>;
  isRegeneratingGrid?: boolean;
  isGeneratingVideo?: boolean;
}

/**
 * 获取流水线步骤状态 (2-step: grid -> video)
 */
const getPipelineStepStatus = (step: SegmentPipelineStep): {
  grid: 'completed' | 'active' | 'pending';
  video: 'completed' | 'active' | 'pending';
} => {
  switch (step) {
    case 'pending':
      return { grid: 'pending', video: 'pending' };
    case 'grid_generating':
      return { grid: 'active', video: 'pending' };
    case 'grid_review':
      return { grid: 'active', video: 'pending' };
    case 'grid_approved':
      return { grid: 'completed', video: 'active' };
    case 'video_generating':
      return { grid: 'completed', video: 'active' };
    case 'video_completed':
      return { grid: 'completed', video: 'completed' };
    case 'video_failed':
      return { grid: 'completed', video: 'active' };
    default:
      return { grid: 'pending', video: 'pending' };
  }
};

/**
 * SegmentCard 组件
 * - 折叠状态：单行显示（状态图标 + 片段编号 + 摘要 + 角色 + 进度指示器）
 * - 展开状态：Header + 左右分栏内容区（GridReviewPanel + VideoPanel）
 */
export const SegmentCard: React.FC<SegmentCardProps> = ({
  episodeId: _episodeId, // eslint-disable-line @typescript-eslint/no-unused-vars
  segment,
  sceneSummary,
  isExpanded,
  onToggle,
  onApproveGrid,
  onRejectGrid,
  onRegenerateGrid,
  onGenerateVideo,
  onLoadVideoPrompt,
  onEnhanceVideoPrompt,
  isRegeneratingGrid,
  isGeneratingVideo,
}) => {
  const stepStatus = getPipelineStepStatus(segment.pipelineStep);
  const [showPromptDetail, setShowPromptDetail] = useState(false);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set(['plot', 'fields', 'grid', 'video']));
  const toggleGroup = (key: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

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
              {(segment.pipelineStep === 'video_generating' || segment.pipelineStep === 'grid_generating') && (
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

          {/* 右侧：缩略图 + 角色头像 + 进度指示器 + 展开箭头 */}
          <div className={styles.headerRight}>
          {/* 缩略图 */}
          {segment.sceneThumbnail ? (
            <div className={styles.sceneThumbnail}>
              <img src={segment.sceneThumbnail} alt="" />
            </div>
          ) : null}

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

          {/* 二步进度指示器: grid -> video */}
          <div className={styles.progressIndicator}>
            {/* 九宫格步骤 */}
            <div
              className={`${styles.stepDot} ${
                stepStatus.grid === 'completed'
                  ? styles.completed
                  : stepStatus.grid === 'active'
                  ? styles.active
                  : styles.pending
              }`}
              title="九宫格"
            >
              <svg width="10" height="10" viewBox="0 0 16 16" fill="none">
                <rect x="1" y="1" width="4" height="4" rx="1" fill="currentColor" />
                <rect x="7" y="1" width="4" height="4" rx="1" fill="currentColor" />
                <rect x="11" y="1" width="4" height="4" rx="1" fill="currentColor" />
                <rect x="1" y="7" width="4" height="4" rx="1" fill="currentColor" />
                <rect x="7" y="7" width="4" height="4" rx="1" fill="currentColor" />
                <rect x="11" y="7" width="4" height="4" rx="1" fill="currentColor" />
                <rect x="1" y="11" width="4" height="4" rx="1" fill="currentColor" />
                <rect x="7" y="11" width="4" height="4" rx="1" fill="currentColor" />
                <rect x="11" y="11" width="4" height="4" rx="1" fill="currentColor" />
              </svg>
            </div>

            {/* 连接线 */}
            <div
              className={`${styles.stepLine} ${
                stepStatus.grid === 'completed' ? styles.completed : styles.pending
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
          {/* 左侧面板 */}
          <div className={styles.panel}>
            {segment.shots.length > 1 ? (
              <PanelGroupView
                fusionImageUrl={segment.fusionImageUrl}
                shots={segment.shots}
                gridStatus={segment.gridStatus}
                onApprove={onApproveGrid}
                onReject={onRejectGrid}
                onRegenerate={onRegenerateGrid}
                isRegenerating={isRegeneratingGrid}
              />
            ) : (
              <GridReviewPanel
                panelId={Number(segment.panelData?.panelId || 0)}
                gridImages={segment.gridImages}
                shots={segment.shots}
                gridStatus={segment.gridStatus}
                gridRejectionFeedback={segment.feedback || null}
                onApprove={onApproveGrid}
                onReject={onRejectGrid}
                onRegenerate={onRegenerateGrid}
                isRegenerating={isRegeneratingGrid}
              />
            )}
          </div>

          {/* 右侧：AI 视频面板 */}
          <div className={styles.panel}>
            <VideoPanel
              videoUrl={segment.videoUrl}
              pipelineStep={segment.pipelineStep}
              onGenerateVideo={onGenerateVideo}
              onRegenerateGrid={onRegenerateGrid}
              isGenerating={isGeneratingVideo}
              isRegeneratingGrid={isRegeneratingGrid}
              videoTaskId={segment.videoTaskId}
              videoOffPeak={segment.videoOffPeak}
              videoProgress={segment.videoProgress}
              videoCredits={segment.videoCredits}
              onLoadPrompt={onLoadVideoPrompt}
              onEnhancePrompt={onEnhanceVideoPrompt}
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
                const shots = segment.shots || [];

                // 拼接多镜头视频提示词（与后端 buildMultiShotPrompt 逻辑一致）
                const videoParts: string[] = [];
                videoParts.push('专业电影级画面。\n\n');
                videoParts.push(`多镜头连续拍摄指令，以下 ${shots.length} 个镜头必须在同一视频中连续呈现：\n\n`);

                shots.forEach((shot: any, idx: number) => {
                  const shotNum = shot.shotNumber || idx + 1;
                  videoParts.push(`【分镜${shotNum}】\n`);
                  videoParts.push(`duration: ${shot.duration}s\n`);
                  videoParts.push(`Scene: ${shot.shotSize || ''}，${shot.cameraAngle || ''}，${shot.cameraMovement || ''}，${shot.visualDescription || ''}\n`);
                  const dialogue = shot.dialogue;
                  if (dialogue && dialogue !== '无') videoParts.push(`对白: ${dialogue}\n`);
                  const audioEffects = shot.audioEffects;
                  if (audioEffects && audioEffects !== '无') videoParts.push(`音效: [${audioEffects}]\n`);
                  videoParts.push('\n');
                });

                videoParts.push('## 画面衔接\n视频应从参考图自然展开，多镜头间平滑过渡。\n');
                videoParts.push('保持角色位置和动作的连贯性。\n');
                videoParts.push(`参考图中编号①②③对应【分镜1】【分镜2】【分镜3】的画面内容。`);
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
                    {/* 分镜信息 */}
                    <div className={styles.promptGroup}>
                      <div className={styles.promptGroupTitle} onClick={() => toggleGroup('fields')}>
                        <span className={styles.promptGroupArrow}>{expandedGroups.has('fields') ? '▼' : '▶'}</span>
                        {shots.length > 1 ? '分组分镜列表' : '分镜字段'}
                      </div>
                      {expandedGroups.has('fields') && (
                      <div className={styles.promptFieldGrid}>
                        {shots.length > 1 ? (
                          shots.map((shot: any, idx: number) => (
                            <div key={idx} className={`${styles.promptFieldItem} ${styles.promptFieldItemFull}`}>
                              <span className={styles.pfLabel}>分镜 {shot.shotNumber || idx + 1}</span>
                              <span className={styles.pfValue}>
                                {shot.duration}s · {shot.shotSize || ''} · {shot.cameraAngle || ''} · {shot.visualDescription || ''}
                              </span>
                            </div>
                          ))
                        ) : (
                          <>
                            {d.composition && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>构图描述</span><span className={styles.pfValue}>{d.composition}</span></div>}
                            {(d.shotType || d.cameraAngle) && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>镜头</span><span className={styles.pfValue}>{d.shotType}{d.cameraAngle ? ` / ${d.cameraAngle}` : ''}</span></div>}
                            {d.pacing && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>节奏</span><span className={styles.pfValue}>{d.pacing}</span></div>}
                            <div className={styles.promptFieldItem}><span className={styles.pfLabel}>时长</span><span className={styles.pfValue}>{d.duration || 5}s</span></div>
                            {d.background?.scene_desc && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>场景描述</span><span className={styles.pfValue}>{d.background.scene_desc}</span></div>}
                            {d.background?.atmosphere && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>氛围</span><span className={styles.pfValue}>{d.background.atmosphere}</span></div>}
                            {d.background?.time_of_day && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>时间</span><span className={styles.pfValue}>{d.background.time_of_day}</span></div>}
                            {d.characters?.length > 0 && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>角色</span><span className={styles.pfValue}>{d.characters.map((c: any) => `${c.name || c.char_id}${c.expression ? `(${c.expression})` : ''}${c.pose ? `[${c.pose}]` : ''}`).join('、')}</span></div>}
                            {d.dialogue && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>对话</span><span className={styles.pfValue}>{d.dialogue}</span></div>}
                            {d.sfx?.length > 0 && <div className={styles.promptFieldItem}><span className={styles.pfLabel}>音效</span><span className={styles.pfValue}>{d.sfx.join('、')}</span></div>}
                            {d.imagePromptHint && <div className={`${styles.promptFieldItem} ${styles.promptFieldItemFull}`}><span className={styles.pfLabel}>画面提示词</span><span className={styles.pfValue}>{d.imagePromptHint}</span></div>}
                          </>
                        )}
                      </div>
                      )}
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

        </div>
      )}
    </div>
  );
};
