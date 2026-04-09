import React, { useCallback, useState } from 'react';
import type { EpisodeState } from '../types';
import { updateShot } from '../../../../services/episodeService';
import styles from '../Step4Production.module.less';

const SpinIcon = () => <span className={styles.btnSpinner} />;

const EDITABLE_FIELDS = [
  'visualDescription', 'narration', 'dialogue', 'speaker',
  'narrationTone', 'dialogueTone', 'shotSize', 'cameraAngle',
  'cameraMovement', 'scene', 'visualEffects', 'audioEffects', 'transitionHint',
] as const;

const FIELD_LABELS: Record<string, string> = {
  visualDescription: '画面描述',
  narration: '旁白',
  dialogue: '对白',
  speaker: '说话人',
  narrationTone: '旁白语气',
  dialogueTone: '对白语气',
  shotSize: '景别',
  cameraAngle: '角度',
  cameraMovement: '运镜',
  scene: '场景',
  visualEffects: '视觉特效',
  audioEffects: '音效',
  transitionHint: '过渡提示',
};

const MULTI_LINE_FIELDS = new Set(['visualDescription', 'narration', 'dialogue']);

interface ScriptEpisodeCardProps {
  episode: EpisodeState;
  projectId: string;
  generatingScript: number | null;
  approvingEpisodeId: number | null;
  expandedEpisodeId: number | null;
  onGenerateScript: (episodeId: number) => void;
  onApproveScript: (episodeId: number) => void;
  onToggleEpisode: (episodeId: number) => void;
  onRefreshEpisode: (episodeId: number) => void;
}

const ScriptEpisodeCard = React.memo(function ScriptEpisodeCard({
  episode, projectId, generatingScript, approvingEpisodeId,
  expandedEpisodeId, onGenerateScript, onApproveScript, onToggleEpisode, onRefreshEpisode,
}: ScriptEpisodeCardProps) {
  const isGenerating = generatingScript === episode.episodeId || generatingScript === -1;
  const isExpanded = expandedEpisodeId === episode.episodeId;
  const hasShots = episode.segments.length > 0;
  const [editData, setEditData] = useState<Record<number, Record<string, string>>>({});
  const [savingIdx, setSavingIdx] = useState<number | null>(null);

  const handleFieldChange = useCallback((shotIdx: number, field: string, value: string) => {
    setEditData(prev => ({
      ...prev,
      [shotIdx]: { ...(prev[shotIdx] || {}), [field]: value },
    }));
  }, []);

  const handleSave = useCallback(async (idx: number) => {
    if (!projectId) return;
    const data = editData[idx];
    if (!data) return;
    setSavingIdx(idx);
    try {
      await updateShot(projectId, episode.episodeId, idx, data);
      setEditData(prev => {
        const next = { ...prev };
        delete next[idx];
        return next;
      });
      onRefreshEpisode(episode.episodeId);
    } catch (err: any) {
      alert(err?.message || '保存失败');
    } finally {
      setSavingIdx(null);
    }
  }, [projectId, episode.episodeId, editData, onRefreshEpisode]);

  const handleToggleLock = useCallback(async (idx: number, currentLocked: boolean) => {
    if (!projectId) return;
    try {
      await updateShot(projectId, episode.episodeId, idx, { locked: !currentLocked });
      onRefreshEpisode(episode.episodeId);
    } catch (err: any) {
      alert(err?.message || '锁定操作失败');
    }
  }, [projectId, episode.episodeId, onRefreshEpisode]);

  const getFieldValue = (shot: any, field: string): string => {
    // 优先显示本地未保存的编辑值，否则显示 shot 原始值
    const localVal = editData[shot.shotNumber - 1]?.[field];
    if (localVal !== undefined) return localVal;
    const val = shot[field];
    return val != null ? String(val) : '';
  };

  return (
    <div className={`${styles.episodeScriptCard} ${isGenerating ? styles.cardGenerating : ''}`}>
      <div className={styles.episodeScriptHeader}>
        <div>
          <h3 className={styles.episodeScriptTitle}>
            第{episode.episodeIndex}集 {episode.title}
          </h3>
          <span className={styles.episodeScriptCount}>
            {hasShots ? `${episode.segments.length} 个分镜` : '暂无分镜数据'}
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
            disabled={isGenerating}
          >
            {isGenerating ? <><SpinIcon /> {hasShots ? '精修中...' : '生成中...'}</> : (hasShots ? '重新生成' : '生成脚本')}
          </button>
          {hasShots && (
            <button
              className={styles.btnSuccess}
              onClick={() => onApproveScript(episode.episodeId)}
              disabled={approvingEpisodeId === episode.episodeId}
            >
              {approvingEpisodeId === episode.episodeId ? <><SpinIcon /> 审核中...</> : '通过'}
            </button>
          )}
        </div>
      </div>

      <button
        className={styles.expandToggle}
        onClick={() => onToggleEpisode(episode.episodeId)}
      >
        {isExpanded ? '收起' : '展开'}分镜文本 &#9660;
      </button>

      {isExpanded && hasShots && (
        <div className={styles.scriptSegmentList}>
          {episode.segments.map((seg, idx) => {
            const shot = episode.episodeInfo?.shots?.[idx];
            const isLocked = shot?.locked === true;
            const shotNum = shot?.shotNumber ?? idx + 1;
            const hasLocalChanges = editData[shotNum - 1] != null;

            return (
              <div key={idx} className={`${styles.scriptSegmentItem} ${isLocked ? styles.shotLocked : ''}`}>
                <div className={styles.scriptSegmentHeader}>
                  <span className={styles.scriptSegmentTitle}>分镜 {idx + 1}</span>
                  {shot?.duration != null && (
                    <span className={styles.shotDuration}>{shot.duration}s</span>
                  )}
                  <button
                    className={`${styles.lockBtn} ${isLocked ? styles.lockBtnActive : ''}`}
                    onClick={() => handleToggleLock(idx, isLocked)}
                    title={isLocked ? '解锁此分镜' : '锁定此分镜（重新生成时保持不变）'}
                  >
                    {isLocked ? '🔒' : '🔓'}
                  </button>
                  <button
                    className={styles.btnSave}
                    onClick={() => handleSave(idx)}
                    disabled={savingIdx === idx || !hasLocalChanges}
                  >
                    {savingIdx === idx ? '保存中...' : '保存'}
                  </button>
                </div>

                <div className={styles.shotFieldList}>
                  {EDITABLE_FIELDS.map(field => (
                    <div key={field} className={styles.shotFieldRow}>
                      <label className={styles.shotFieldLabel}>{FIELD_LABELS[field]}</label>
                      <textarea
                        className={styles.shotFieldInput}
                        value={getFieldValue(shot, field)}
                        onChange={e => handleFieldChange(shotNum - 1, field, e.target.value)}
                        rows={MULTI_LINE_FIELDS.has(field) ? 2 : 1}
                      />
                    </div>
                  ))}
                </div>

                {seg.characterAvatars.length > 0 && (
                  <div className={styles.scriptSegmentCharacters}>
                    <span>角色：</span>{seg.characterAvatars.map(a => a.name).join('、')}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
});

export default ScriptEpisodeCard;
