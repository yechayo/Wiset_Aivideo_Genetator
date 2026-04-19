import React, { useCallback } from 'react';
import type { EpisodeState, SegmentState } from '../types';
import styles from '../Step4Production.module.less';

const SpinIcon = () => <span className={styles.btnSpinner} />;
const PlayIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <polygon points="5 3 19 12 5 21 5 3" />
  </svg>
);

interface VideoSegmentRowProps {
  episode: EpisodeState;
  segment: SegmentState;
  segmentIndex: number;
  isComicCommentary: boolean;
  isVideoRefMode?: boolean;
  generatingVideoKeys: Set<string>;
  expandedPanelKey: string | null;
  onGenerateVideo: (episodeId: number, panelId: string) => void;
  onGenerateTts: (episodeId: number, panelId: string) => void;
  onMergeAudio: (episodeId: number, panelId: string) => void;
  onTogglePanel: (panelKey: string | null) => void;
  onOpenPromptModal: (episodeId: number, panelId: string) => void;
  onOpenLightbox: (url: string) => void;
}

const VideoSegmentRow = React.memo(function VideoSegmentRow({
  episode,
  segment,
  segmentIndex,
  isComicCommentary,
  isVideoRefMode = false,
  generatingVideoKeys,
  expandedPanelKey,
  onGenerateVideo,
  onGenerateTts,
  onMergeAudio,
  onTogglePanel,
  onOpenPromptModal,
  onOpenLightbox,
}: VideoSegmentRowProps) {
  const panelId = segment.panelData?.panelId;
  const panelKey = `${episode.episodeId}-${panelId}`;
  const isGenerating = generatingVideoKeys.has(panelKey);
  const isFailed = segment.pipelineStep === 'video_failed';
  const isDone = !isGenerating && (!!segment.videoUrl || segment.pipelineStep === 'video_completed');
  const isExpanded = expandedPanelKey === panelKey;

  const shotEntries = (segment.shots || [])
    .map((s: any) => {
      const desc = s.visualDescription || s.visual_description || s.scene || '';
      let dialogueText = '';
      if (typeof s.dialogue === 'string' && s.dialogue && s.dialogue !== '无') {
        const speaker = s.speaker && s.speaker !== '无' ? `${s.speaker}：` : '';
        dialogueText = speaker + s.dialogue;
      } else if (Array.isArray(s.dialogue)) {
        dialogueText = s.dialogue.map((d: any) => d.speaker ? `${d.speaker}：${d.text}` : d.text).join('\n');
      }
      return { desc, dialogue: dialogueText };
    })
    .filter(e => e.desc || e.dialogue);

  const handleGenerateVideo = useCallback(() => {
    if (panelId) onGenerateVideo(episode.episodeId, panelId);
  }, [episode.episodeId, panelId, onGenerateVideo]);

  const handleRetryVideo = useCallback(() => {
    if (panelId) onGenerateVideo(episode.episodeId, panelId);
  }, [episode.episodeId, panelId, onGenerateVideo]);

  const handleGenerateTts = useCallback(() => {
    if (panelId) onGenerateTts(episode.episodeId, panelId);
  }, [episode.episodeId, panelId, onGenerateTts]);

  const handleMergeAudio = useCallback(() => {
    if (panelId) onMergeAudio(episode.episodeId, panelId);
  }, [episode.episodeId, panelId, onMergeAudio]);

  const handleToggleExpand = useCallback(() => {
    onTogglePanel(isExpanded ? null : panelKey);
  }, [isExpanded, panelKey, onTogglePanel]);

  const handleOpenPromptModal = useCallback(() => {
    if (panelId) onOpenPromptModal(episode.episodeId, panelId);
  }, [episode.episodeId, panelId, onOpenPromptModal]);

  return (
    <div className={styles.panelVideoCard}>
      <div className={styles.panelVideoRow}>
        <span className={styles.panelVideoNumber}>
          {segment.title}
        </span>
        <span className={styles.panelVideoSynopsis}>
          {segment.synopsis}
        </span>
        {isDone ? (
          <span className={styles.panelVideoStatus}>已完成</span>
        ) : isGenerating && !isFailed ? (
          <span className={styles.panelVideoGenerating}>
            <SpinIcon /> {segment.videoProgress != null ? `${segment.videoProgress}%` : '生成中...'}
          </span>
        ) : isFailed ? (
          <span className={styles.panelVideoFailed}>
            生成失败
            <button
              className={styles.btnPrimary}
              style={{ marginLeft: 8 }}
              onClick={handleRetryVideo}
              disabled={!panelId}
            >
              重试
            </button>
          </span>
        ) : (
          <button
            className={styles.btnPrimary}
            onClick={handleGenerateVideo}
            disabled={!panelId}
          >
            生成视频
          </button>
        )}
        {segment.videoUrl && (
          <button
            className={styles.panelVideoPreview}
            onClick={handleToggleExpand}
          >
            {isExpanded ? '收起' : <><PlayIcon /> 预览</>}
          </button>
        )}
        {panelId && (
          <button
            className={styles.btnGhost}
            onClick={handleOpenPromptModal}
          >
            提示词
          </button>
        )}
        <button
          className={styles.panelExpandBtn}
          onClick={handleToggleExpand}
        >
          {isExpanded ? '收起' : '详情'} &#9660;
        </button>
      </div>

      {/* 视频播放器 */}
      {isExpanded && segment.videoUrl && (
        <div className={styles.panelVideoPlayerWrap}>
          <video
            key={segment.videoUrl}
            className={styles.panelVideoPlayer}
            controls
            autoPlay
            src={segment.videoUrl}
          />
        </div>
      )}

      {/* 进度条 */}
      {isGenerating && (
        <div className={styles.panelVideoProgressBar}>
          <div
            className={styles.panelVideoProgressFill}
            style={{ width: `${segment.videoProgress || 0}%` }}
          />
        </div>
      )}

      {/* 元信息：积分、任务ID、错峰 */}
      {(segment.videoCredits != null || segment.videoTaskId || segment.videoOffPeak || segment.videoModel) && (
        <div className={styles.panelVideoMeta}>
          {segment.videoModel && (
            <span className={styles.panelVideoTag}>
              {segment.videoModel === 'pro' ? 'Pro'
                : segment.videoModel === 'turbo' ? 'Turbo'
                : segment.videoModel === 'kling-v3-omni-std' ? 'Kling Std'
                : segment.videoModel === 'kling-v3-omni-pro' ? 'Kling Pro'
                : segment.videoModel}
            </span>
          )}
          {segment.videoOffPeak && <span className={styles.panelVideoTag}>错峰</span>}
          {segment.videoCredits != null && <span className={styles.panelVideoTag}>{segment.videoCredits} 积分</span>}
        </div>
      )}

      {isExpanded && (
        <div className={`${styles.panelDetailContent} ${styles.twoColumn}`}>
          {/* Left column: video content */}
          <div className={styles.leftColumn}>
            {!isVideoRefMode && segment.fusionImageUrl && (
              <div className={styles.panelDetailSection}>
                <span className={styles.panelDetailLabel}>融合参考图</span>
                <img
                  src={segment.fusionImageUrl}
                  alt="融合参考图"
                  className={`${styles.panelFusionImage} ${styles.clickableImage}`}
                  onClick={() => onOpenLightbox(segment.fusionImageUrl!)}
                />
              </div>
            )}
            {isVideoRefMode && (
              <div className={styles.panelDetailSection}>
                <span className={styles.panelDetailLabel}>参考图（{segment.referenceImages?.length || 0}张）</span>
                {segment.referenceImages && segment.referenceImages.length > 0 && (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {segment.referenceImages.map((url: string, idx: number) => (
                      <img
                        key={idx}
                        src={url}
                        alt={segment.referenceImageLabels?.[idx] || `参考图${idx + 1}`}
                        title={segment.referenceImageLabels?.[idx] || `参考图${idx + 1}`}
                        className={`${styles.panelFusionImage} ${styles.clickableImage}`}
                        style={{ width: 80, height: 80, objectFit: 'cover', borderRadius: 6 }}
                        onClick={() => onOpenLightbox(url)}
                      />
                    ))}
                  </div>
                )}
                {(!segment.referenceImages || segment.referenceImages.length === 0) && (
                  <span style={{ fontSize: 'var(--font-size-sm)', color: 'var(--color-text-muted)' }}>
                    分镜切分图 + 角色图
                  </span>
                )}
              </div>
            )}
            {shotEntries.length > 0 && (
              <div className={styles.panelDetailSection}>
                <span className={styles.panelDetailLabel}>分镜描述</span>
                <div className={styles.panelPromptList}>
                  {shotEntries.map((entry, sIdx) => (
                    <div key={sIdx} className={styles.panelPromptItem}>
                      <span className={styles.panelPromptIndex}>{sIdx + 1}</span>
                      <div className={styles.panelPromptContent}>
                        {entry.desc && <span className={styles.panelPromptText}>{entry.desc}</span>}
                        {entry.dialogue && <span className={styles.panelPromptDialogue}>{entry.dialogue}</span>}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* Right column: TTS narration management */}
          {isComicCommentary && (
          <div className={styles.rightColumn}>
            <div className={styles.narrationHeader}>
              <span>旁白语音</span>
              <button
                className={styles.btnPrimary}
                disabled={segment.ttsStatus === 'generating' || !panelId}
                onClick={handleGenerateTts}
              >
                {segment.ttsStatus === 'generating' ? <><SpinIcon /> 生成中...</> : segment.ttsStatus === 'completed' || segment.ttsAudioUrl ? '重新生成' : '生成旁白'}
              </button>
            </div>
            <div className={styles.shotList}>
              {(segment.shots || []).map((shot: any, sIdx: number) => {
                const hasDialogue = !!(shot.dialogue && shot.dialogue !== '无' && shot.dialogue !== '');
                const narration = shot.narration || (shot.speaker === '旁白' ? shot.dialogue : '') || '';
                return (
                  <div key={sIdx} className={styles.shotItem}>
                    <span className={styles.shotLabel}>分镜{sIdx + 1}</span>
                    <span className={hasDialogue ? styles.hasDialogue : styles.hasNarration}>
                      {hasDialogue ? `[台词] ${shot.dialogue}` : `[旁白] ${narration || '—'}`}
                    </span>
                  </div>
                );
              })}
            </div>
            {(segment.ttsStatus === 'completed' || segment.ttsAudioUrl) && (
              <div className={styles.ttsPlayer}>
                <audio controls src={segment.ttsAudioUrl!} style={{ width: '100%' }} />
              </div>
            )}
            {segment.ttsStatus === 'failed' && (
              <div className={styles.gridRejectionFeedback}>
                旁白生成失败，请重试
              </div>
            )}
            {(segment.ttsStatus === 'completed' || segment.ttsAudioUrl) && (
              <div style={{ marginTop: 12 }}>
                <button
                  className={styles.btnPrimary}
                  disabled={segment.mergeStatus === 'generating' || !panelId}
                  onClick={handleMergeAudio}
                >
                  {segment.mergeStatus === 'generating' ? <><SpinIcon /> 合成中...</> : segment.mergeStatus === 'completed' ? '重新合成' : '合成旁白视频'}
                </button>
                {segment.mergeStatus === 'completed' && segment.videoWithNarrationUrl && (
                  <div style={{ marginTop: 8 }}>
                    <video
                      controls
                      src={segment.videoWithNarrationUrl}
                      style={{ width: '100%', maxHeight: 200 }}
                    />
                  </div>
                )}
                {segment.mergeStatus === 'failed' && (
                  <span style={{ color: '#ff4d4f', fontSize: 12 }}>合成失败，请重试</span>
                )}
              </div>
            )}
          </div>
          )}
        </div>
      )}
    </div>
  );
});

export default VideoSegmentRow;
