import React, { useCallback } from 'react';
import type { EpisodeState } from '../types';
import styles from '../Step4Production.module.less';

const SpinIcon = () => <span className={styles.btnSpinner} />;

interface ScriptEpisodeCardProps {
  episode: EpisodeState;
  generatingScript: number | null;
  approvingEpisodeId: number | null;
  rejectingEpisodeId: number | null;
  expandedEpisodeId: number | null;
  onGenerateScript: (episodeId: number) => void;
  onApproveScript: (episodeId: number) => void;
  onRejectScript: (episodeId: number, reason: string) => void;
  onToggleEpisode: (episodeId: number) => void;
}

const ScriptEpisodeCard = React.memo(function ScriptEpisodeCard({
  episode,
  generatingScript,
  approvingEpisodeId,
  rejectingEpisodeId,
  expandedEpisodeId,
  onGenerateScript,
  onApproveScript,
  onRejectScript,
  onToggleEpisode,
}: ScriptEpisodeCardProps) {
  const isGenerating = generatingScript === episode.episodeId || generatingScript === -1;
  const isExpanded = expandedEpisodeId === episode.episodeId;

  const handleRejectClick = useCallback(() => {
    const reason = prompt('请给出你的优化建议:');
    if (reason) onRejectScript(episode.episodeId, reason);
  }, [episode.episodeId, onRejectScript]);

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
          <div className={styles.scriptStageList}>
            <span className={`${styles.scriptStageItem} ${episode.scriptStatus === 'done' ? styles.scriptStageItemDone : episode.scriptStatus === 'generating' ? styles.scriptStageItemActive : styles.scriptStageItemPending}`}>
              {episode.scriptStatus === 'generating' ? '⏳ Stage 1: 剧本生成中...' : episode.scriptStatus === 'done' ? '✓ Stage 1: 剧本完成' : '○ Stage 1: 待生成'}
            </span>
            <span className={`${styles.scriptStageItem} ${episode.storyboardStatus === 'done' ? styles.scriptStageItemDone : episode.storyboardStatus === 'generating' ? styles.scriptStageItemActive : styles.scriptStageItemPending}`}>
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
        </div>
      )}
    </div>
  );
});

export default ScriptEpisodeCard;
