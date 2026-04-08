import React, { useCallback } from 'react';
import type { EpisodeState } from '../types';
import styles from '../Step4Production.module.less';

const SpinIcon = () => <span className={styles.btnSpinner} />;

interface ScriptEpisodeCardProps {
  episode: EpisodeState;
  project: any;
  generatingScript: number | null;
  approvingEpisodeId: number | null;
  rejectingEpisodeId: number | null;
  expandedEpisodeId: number | null;
  expandedPanelKey: string | null;
  onGenerateScript: (episodeId: number) => void;
  onApproveScript: (episodeId: number) => void;
  onRejectScript: (episodeId: number, reason: string) => void;
  onToggleEpisode: (episodeId: number) => void;
  onTogglePromptPreview: (key: string | null) => void;
  buildGridPromptText: (visualStyle: string, shots: any[], isComicCommentary?: boolean) => string;
  buildMultiShotPromptText: (visualStyle: string, shots: any[], isComicCommentary?: boolean) => string;
}

const ScriptEpisodeCard = React.memo(function ScriptEpisodeCard({
  episode,
  project,
  generatingScript,
  approvingEpisodeId,
  rejectingEpisodeId,
  expandedEpisodeId,
  expandedPanelKey,
  onGenerateScript,
  onApproveScript,
  onRejectScript,
  onToggleEpisode,
  onTogglePromptPreview,
  buildGridPromptText,
  buildMultiShotPromptText,
}: ScriptEpisodeCardProps) {
  const isGenerating = generatingScript === episode.episodeId || generatingScript === -1;
  const isExpanded = expandedEpisodeId === episode.episodeId;
  const isComicCommentary = project?.projectInfo?.productionMode === 'comic_commentary';

  const handleRejectClick = useCallback(() => {
    const reason = prompt('请给出你的优化建议:');
    if (reason) onRejectScript(episode.episodeId, reason);
  }, [episode.episodeId, onRejectScript]);

  const allShots = episode.segments.map(s => s.shots?.[0]).filter(Boolean);
  const visualStyle = episode.segments[0]?.panelData?.visualStyle || 'ANIME';
  const promptImageKey = `prompt-image-${episode.episodeId}`;
  const promptVideoKey = `prompt-video-${episode.episodeId}`;

  return (
    <div className={`${styles.episodeScriptCard} ${isGenerating ? styles.cardGenerating : ''}`}>
      <div className={styles.episodeScriptHeader}>
        <div>
          <h3 className={styles.episodeScriptTitle}>
            第{episode.episodeIndex}集 {episode.title}
          </h3>
          <span className={styles.episodeScriptCount}>
            {episode.segments.length > 0 ? `${episode.segments.length} 个分镜` : '暂无分镜数据'}
          </span>
          <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
            <span style={{
              fontSize: 12,
              color: episode.scriptStatus === 'done' ? '#52c41a' : episode.scriptStatus === 'generating' ? '#1890ff' : '#999',
            }}>
              {episode.scriptStatus === 'generating' ? '⏳ Stage 1: 剧本生成中...' : episode.scriptStatus === 'done' ? '✓ Stage 1: 剧本完成' : '○ Stage 1: 待生成'}
            </span>
            <span style={{
              fontSize: 12,
              color: episode.storyboardStatus === 'done' ? '#52c41a' : episode.storyboardStatus === 'generating' ? '#1890ff' : '#999',
            }}>
              {episode.storyboardStatus === 'generating' ? '⏳ Stage 2: 旁白精修中...' : episode.storyboardStatus === 'done' ? '✓ Stage 2: 旁白精修完成' : '○ Stage 2: 待精修'}
            </span>
          </div>
        </div>
        <div className={styles.episodeScriptActions}>
          <button
            className={styles.btnPrimary}
            onClick={() => onGenerateScript(episode.episodeId)}
            disabled={generatingScript === episode.episodeId || generatingScript === -1}
          >
            {(generatingScript === episode.episodeId || generatingScript === -1) ? <><SpinIcon /> 生成中...</> : '生成脚本'}
          </button>
          {episode.segments.length > 0 && (
            <>
              <button
                className={styles.btnSuccess}
                onClick={() => onApproveScript(episode.episodeId)}
                disabled={approvingEpisodeId === episode.episodeId || rejectingEpisodeId === episode.episodeId}
              >
                {approvingEpisodeId === episode.episodeId ? <><SpinIcon /> 审核中...</> : '通过'}
              </button>
              <button
                className={styles.btnDanger}
                onClick={handleRejectClick}
                disabled={approvingEpisodeId === episode.episodeId || rejectingEpisodeId === episode.episodeId}
              >
                {rejectingEpisodeId === episode.episodeId ? <><SpinIcon /> 退回中...</> : '退回'}
              </button>
            </>
          )}
        </div>
      </div>

      <button
        className={styles.expandToggle}
        onClick={() => onToggleEpisode(episode.episodeId)}
      >
        {isExpanded ? '收起' : '展开'}分镜文本 &#9660;
      </button>

      {isExpanded && episode.segments.length > 0 && (
        <div className={styles.scriptSegmentList}>
          {episode.segments.map((seg, idx) => (
            <div key={idx} className={styles.scriptSegmentItem}>
              <div className={styles.scriptSegmentTitle}>分镜 {idx + 1}</div>
              {seg.synopsis && (
                <div className={styles.scriptSegmentDetail}>
                  <span>画面：</span>{seg.synopsis}
                </div>
              )}
              {seg.panelData?.dialogue && (
                <div className={styles.scriptSegmentDetail}>
                  <span>对话：</span><span style={{ whiteSpace: 'pre-wrap' }}>{seg.panelData.dialogue}</span>
                </div>
              )}
              {seg.characterAvatars.length > 0 && (
                <div className={styles.scriptSegmentCharacters}>
                  <span>角色：</span>{seg.characterAvatars.map(a => a.name).join('、')}
                </div>
              )}
              {seg.panelData?.composition && (
                <div className={styles.scriptSegmentDetail}>
                  <span>镜头：</span>{seg.panelData.composition}
                  {seg.panelData?.cameraAngle && ` / ${seg.panelData.cameraAngle}`}
                  {seg.panelData?.cameraMovement && ` / ${seg.panelData.cameraMovement}`}
                </div>
              )}
            </div>
          ))}

          {episode.segments.length > 0 && allShots.length > 0 && (
            <div className={styles.episodePromptPreview}>
              <button
                className={styles.episodePromptToggle}
                onClick={() => onTogglePromptPreview(expandedPanelKey === promptImageKey ? null : promptImageKey)}
              >
                图片生成 Prompt（九宫格）
                <span className={styles.episodePromptArrow}>
                  {expandedPanelKey === promptImageKey ? '▾' : '▸'}
                </span>
              </button>
              {expandedPanelKey === promptImageKey && (
                <pre className={styles.episodePromptBlock}>
                  {buildGridPromptText(visualStyle, allShots, isComicCommentary)}
                </pre>
              )}
              <button
                className={styles.episodePromptToggle}
                onClick={() => onTogglePromptPreview(expandedPanelKey === promptVideoKey ? null : promptVideoKey)}
              >
                视频生成 Prompt（多镜头）
                <span className={styles.episodePromptArrow}>
                  {expandedPanelKey === promptVideoKey ? '▾' : '▸'}
                </span>
              </button>
              {expandedPanelKey === promptVideoKey && (
                <pre className={styles.episodePromptBlock}>
                  {buildMultiShotPromptText(visualStyle, allShots, isComicCommentary)}
                </pre>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
});

export default ScriptEpisodeCard;
