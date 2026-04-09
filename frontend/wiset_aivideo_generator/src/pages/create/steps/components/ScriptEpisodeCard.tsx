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

const MULTI_LINE_FIELDS = new Set(['visualDescription', 'narration', 'dialogue', 'scene']);

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
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [editData, setEditData] = useState<Record<string, string>>({});
  const [savingIdx, setSavingIdx] = useState<number | null>(null);

  const handleEdit = useCallback((idx: number) => {
    const seg = episode.segments[idx];
    const shot = seg?.shots?.[0] || episode.episodeInfo?.shots?.[idx];
    const pd = seg?.panelData;

    const data: Record<string, string> = {};
    // 从 shot 读取字段，fallback 到 seg 的显示数据
    const trySet = (field: string, fallback?: string) => {
      const val = shot?.[field];
      if (val != null) {
        data[field] = String(val);
      } else if (fallback != null && fallback !== '') {
        data[field] = fallback;
      }
    };

    trySet('visualDescription', seg?.synopsis);
    trySet('narration', shot?.narration);
    trySet('dialogue', pd?.dialogue);
    trySet('speaker', shot?.speaker);
    trySet('narrationTone', shot?.narrationTone);
    trySet('dialogueTone', shot?.dialogueTone);
    trySet('shotSize', pd?.composition);
    trySet('cameraAngle', pd?.cameraAngle);
    trySet('cameraMovement', pd?.cameraMovement);
    trySet('scene', pd?.scene);
    trySet('visualEffects', shot?.visualEffects);
    trySet('audioEffects', shot?.audioEffects);
    trySet('transitionHint', shot?.transitionHint);

    setEditData(data);
    setEditingIdx(idx);
  }, [episode.segments, episode.episodeInfo?.shots]);

  const handleSave = useCallback(async (idx: number) => {
    if (!projectId) return;
    setSavingIdx(idx);
    try {
      await updateShot(projectId, episode.episodeId, idx, editData);
      setEditingIdx(null);
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

  const handleFieldChange = useCallback((field: string, value: string) => {
    setEditData(prev => ({ ...prev, [field]: value }));
  }, []);

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
            const shot = seg.shots?.[0] || episode.episodeInfo?.shots?.[idx];
            const isLocked = shot?.locked === true;
            const isEditing = editingIdx === idx;

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
                    aria-label={isLocked ? '解锁此分镜' : '锁定此分镜'}
                  >
                    {isLocked ? (
                      <svg viewBox="0 0 16 16" fill="none" aria-hidden="true" className={styles.lockBtnIcon}>
                        <path d="M5.5 6V4.75a2.5 2.5 0 1 1 5 0V6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
                        <rect x="3.75" y="6" width="8.5" height="6.75" rx="1.8" stroke="currentColor" strokeWidth="1.4" />
                        <circle cx="8" cy="9.2" r="0.8" fill="currentColor" />
                        <path d="M8 10v1.15" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
                      </svg>
                    ) : (
                      <svg viewBox="0 0 16 16" fill="none" aria-hidden="true" className={styles.lockBtnIcon}>
                        <path d="M10.5 6V4.75a2.5 2.5 0 1 0-5 0" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
                        <rect x="3.75" y="6" width="8.5" height="6.75" rx="1.8" stroke="currentColor" strokeWidth="1.4" />
                        <circle cx="8" cy="9.2" r="0.8" fill="currentColor" />
                        <path d="M8 10v1.15" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
                      </svg>
                    )}
                  </button>
                  {isEditing ? (
                    <div className={styles.shotEditActions}>
                      <button
                        className={styles.btnSave}
                        onClick={() => handleSave(idx)}
                        disabled={savingIdx === idx}
                      >
                        {savingIdx === idx ? '保存中...' : '保存'}
                      </button>
                      <button
                        className={styles.btnCancel}
                        onClick={() => setEditingIdx(null)}
                      >
                        取消
                      </button>
                    </div>
                  ) : (
                    <button className={styles.btnEdit} onClick={() => handleEdit(idx)}>
                      编辑
                    </button>
                  )}
                </div>

                <div className={styles.scriptSegmentBody}>
                    {/* 画面描述 */}
                    <div className={styles.scriptSegmentDetail}>
                      <span>画面：</span>
                      {isEditing ? (
                        <textarea
                          className={styles.shotInlineInput}
                          value={editData['visualDescription'] ?? seg.synopsis ?? ''}
                          onChange={e => handleFieldChange('visualDescription', e.target.value)}
                          rows={2}
                        />
                      ) : seg.synopsis}
                    </div>
                    {/* 旁白 */}
                    {isEditing && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>旁白：</span>
                        <textarea
                          className={styles.shotInlineInput}
                          value={editData['narration'] ?? shot?.narration ?? ''}
                          onChange={e => handleFieldChange('narration', e.target.value)}
                          rows={2}
                        />
                      </div>
                    )}
                    {/* 对白 */}
                    {(isEditing || (seg.panelData?.dialogue && seg.panelData.dialogue !== '无')) && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>对话：</span>
                        {isEditing ? (
                          <textarea
                            className={styles.shotInlineInput}
                            value={editData['dialogue'] ?? seg.panelData?.dialogue ?? ''}
                            onChange={e => handleFieldChange('dialogue', e.target.value)}
                            rows={2}
                          />
                        ) : (
                          <span style={{ whiteSpace: 'pre-wrap' }}>{seg.panelData?.dialogue}</span>
                        )}
                      </div>
                    )}
                    {/* 说话人 */}
                    {isEditing && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>说话人：</span>
                        <input
                          className={styles.shotInlineInput}
                          value={editData['speaker'] ?? shot?.speaker ?? ''}
                          onChange={e => handleFieldChange('speaker', e.target.value)}
                        />
                      </div>
                    )}
                    {/* 镜头参数 */}
                    {(isEditing || seg.panelData?.composition) && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>镜头：</span>
                        {isEditing ? (
                          <div className={styles.shotCameraRow}>
                            <input className={styles.shotInlineInput} placeholder="景别"
                              value={editData['shotSize'] ?? seg.panelData?.composition ?? ''} onChange={e => handleFieldChange('shotSize', e.target.value)} />
                            <input className={styles.shotInlineInput} placeholder="角度"
                              value={editData['cameraAngle'] ?? seg.panelData?.cameraAngle ?? ''} onChange={e => handleFieldChange('cameraAngle', e.target.value)} />
                            <input className={styles.shotInlineInput} placeholder="运镜"
                              value={editData['cameraMovement'] ?? seg.panelData?.cameraMovement ?? ''} onChange={e => handleFieldChange('cameraMovement', e.target.value)} />
                          </div>
                        ) : (
                          <>{seg.panelData?.composition}
                          {seg.panelData?.cameraAngle && ` / ${seg.panelData.cameraAngle}`}
                          {seg.panelData?.cameraMovement && ` / ${seg.panelData.cameraMovement}`}
                          </>
                        )}
                      </div>
                    )}
                    {/* 场景 */}
                    {isEditing && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>场景：</span>
                        <textarea
                          className={styles.shotInlineInput}
                          value={editData['scene'] ?? shot?.scene ?? ''}
                          onChange={e => handleFieldChange('scene', e.target.value)}
                          rows={1}
                        />
                      </div>
                    )}
                    {/* 语气 & 特效 */}
                    {isEditing && (
                      <div className={styles.shotInlineRow}>
                        <div className={styles.scriptSegmentDetail}>
                          <span>旁白语气：</span>
                          <input className={styles.shotInlineInput}
                            value={editData['narrationTone'] ?? shot?.narrationTone ?? ''} onChange={e => handleFieldChange('narrationTone', e.target.value)} />
                        </div>
                        <div className={styles.scriptSegmentDetail}>
                          <span>对白语气：</span>
                          <input className={styles.shotInlineInput}
                            value={editData['dialogueTone'] ?? shot?.dialogueTone ?? ''} onChange={e => handleFieldChange('dialogueTone', e.target.value)} />
                        </div>
                      </div>
                    )}
                    {isEditing && (
                      <div className={styles.shotInlineRow}>
                        <div className={styles.scriptSegmentDetail}>
                          <span>视觉特效：</span>
                          <input className={styles.shotInlineInput}
                            value={editData['visualEffects'] ?? shot?.visualEffects ?? ''} onChange={e => handleFieldChange('visualEffects', e.target.value)} />
                        </div>
                        <div className={styles.scriptSegmentDetail}>
                          <span>音效：</span>
                          <input className={styles.shotInlineInput}
                            value={editData['audioEffects'] ?? shot?.audioEffects ?? ''} onChange={e => handleFieldChange('audioEffects', e.target.value)} />
                        </div>
                      </div>
                    )}
                    {isEditing && (
                      <div className={styles.scriptSegmentDetail}>
                        <span>过渡：</span>
                        <input className={styles.shotInlineInput}
                          value={editData['transitionHint'] ?? shot?.transitionHint ?? ''} onChange={e => handleFieldChange('transitionHint', e.target.value)} />
                      </div>
                    )}
                    {/* 角色（始终只读） */}
                    {seg.characterAvatars.length > 0 && (
                      <div className={styles.scriptSegmentCharacters}>
                        <span>角色：</span>{seg.characterAvatars.map(a => a.name).join('、')}
                      </div>
                    )}
                  </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
});

export default ScriptEpisodeCard;
