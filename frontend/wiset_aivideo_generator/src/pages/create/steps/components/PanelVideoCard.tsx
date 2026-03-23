import styles from './PanelVideoCard.module.less';
import type { PanelState } from '../../../../services/types/episode.types';

interface PanelVideoCardProps {
  panel: PanelState;
  onGenerateVideo: (panelIndex: number) => void;
  // 新增：场景图相关（用于阶段2-4）
  sceneImageUrl?: string | null;
  sceneImagePrompt?: string | null;
  sceneGenerating?: boolean;
  // 新增：融合图（阶段4需要显示融合图）
  fusionImageUrl?: string | null;
  // 新增：操作回调
  onRegenerateScene?: () => void;
  onAutoFusion?: () => void;
  onManualFusion?: () => void;
}

/** 截断文本，超出 maxLength 时添加省略号 */
const truncate = (text: string | null | undefined, maxLength: number): string => {
  if (!text) return '';
  if (text.length <= maxLength) return text;
  return text.slice(0, maxLength) + '...';
};

const PanelVideoCard = ({
  panel,
  onGenerateVideo,
  sceneImageUrl,
  sceneImagePrompt,
  sceneGenerating,
  fusionImageUrl,
  onRegenerateScene,
  onAutoFusion,
  onManualFusion,
}: PanelVideoCardProps) => {
  const {
    panelIndex,
    fusionStatus,
    fusionUrl,
    videoStatus,
    videoUrl,
    panelId,
    shotType,
    sceneDescription,
    dialogue,
  } = panel;

  // 三图布局（阶段4：场景图 + 融合图 + 视频）
  const hasSceneImage = !!sceneImageUrl;
  const hasFusionImage = !!(fusionImageUrl || (fusionStatus === 'completed' && fusionUrl));
  const effectiveFusionUrl = fusionImageUrl || fusionUrl;

  if (hasSceneImage || sceneGenerating) {
    return (
      <div className={styles.card}>
        <div className={styles.mediaRow}>
          {/* 左侧：场景图 */}
          <div className={styles.mediaThird}>
            <div className={styles.mediaBox}>
              {sceneGenerating ? (
                <div className={styles.placeholder}>
                  <span className={styles.spinner} />
                  <span className={styles.placeholderText}>生成中...</span>
                </div>
              ) : sceneImageUrl ? (
                <>
                  <img src={sceneImageUrl!} alt="场景图" />
                  {onRegenerateScene && (
                    <button className={styles.regenBtn} onClick={onRegenerateScene}>重新生成</button>
                  )}
                </>
              ) : (
                <div className={styles.placeholder}>等待场景图</div>
              )}
            </div>
            {sceneImagePrompt && (
              <div className={styles.promptLabel}>Prompt: {sceneImagePrompt}</div>
            )}
          </div>

          {/* 中间：融合图 */}
          <div className={styles.mediaThird}>
            <div className={styles.mediaBox}>
              {hasFusionImage && effectiveFusionUrl ? (
                <>
                  <img src={effectiveFusionUrl} alt="融合图" />
                  <button onClick={onManualFusion}>重新融合</button>
                </>
              ) : (
                <div className={styles.placeholder}>
                  {onAutoFusion && !sceneGenerating && (
                    <button className={styles.generateButton} onClick={onAutoFusion}>
                      自动融合
                    </button>
                  )}
                  {!onAutoFusion && '等待融合'}
                </div>
              )}
            </div>
          </div>

          {/* 右侧：视频 */}
          <div className={styles.mediaThird}>
            <div className={styles.mediaBox}>
              {videoStatus === 'completed' && videoUrl ? (
                <video
                  className={styles.mediaContent}
                  src={videoUrl}
                  controls
                  preload="metadata"
                />
              ) : videoStatus === 'generating' ? (
                <div className={styles.placeholder}>
                  <span className={styles.spinner} />
                  <span className={styles.placeholderText}>生成中...</span>
                </div>
              ) : videoStatus === 'failed' ? (
                <div className={styles.placeholder}>
                  <span className={styles.errorText}>生成失败</span>
                  <button
                    className={styles.actionButton}
                    onClick={() => onGenerateVideo(panelIndex)}
                  >
                    重试
                  </button>
                </div>
              ) : (
                <div className={styles.placeholder}>
                  <button
                    className={styles.generateButton}
                    onClick={() => onGenerateVideo(panelIndex)}
                  >
                    生成视频
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* 底部信息栏 */}
        <div className={styles.infoBar}>
          <div className={styles.infoMeta}>
            {panelId && <span className={styles.panelId}>{panelId}</span>}
            {shotType && <span className={styles.shotType}>{shotType}</span>}
          </div>
          {(sceneDescription || dialogue) && (
            <div className={styles.infoDesc}>
              {sceneDescription && (
                <p className={styles.descText}>
                  {truncate(sceneDescription, 60)}
                </p>
              )}
              {dialogue && (
                <p className={styles.descText}>
                  <span className={styles.dialogueLabel}>"</span>
                  {truncate(dialogue, 50)}
                  <span className={styles.dialogueLabel}>"</span>
                </p>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className={styles.card}>
      <div className={styles.mediaRow}>
        {/* ---- 左侧：融合场景图 ---- */}
        <div className={styles.mediaHalf}>
          <div className={styles.mediaBox}>
            {fusionStatus === 'completed' && fusionUrl ? (
              <img
                className={styles.mediaContent}
                src={fusionUrl}
                alt={`面板 ${panelIndex + 1} 融合图`}
              />
            ) : (
              <div className={styles.placeholder}>
                <span className={styles.placeholderText}>等待融合</span>
              </div>
            )}
          </div>
        </div>

        {/* ---- 右侧：视频区域 ---- */}
        <div className={styles.mediaHalf}>
          <div className={styles.mediaBox}>
            {videoStatus === 'completed' && videoUrl ? (
              <video
                className={styles.mediaContent}
                src={videoUrl}
                controls
                preload="metadata"
              />
            ) : videoStatus === 'generating' ? (
              <div className={styles.placeholder}>
                <span className={styles.spinner} />
                <span className={styles.placeholderText}>生成中...</span>
              </div>
            ) : videoStatus === 'failed' ? (
              <div className={styles.placeholder}>
                <span className={styles.errorText}>生成失败</span>
                <button
                  className={styles.actionButton}
                  onClick={() => onGenerateVideo(panelIndex)}
                >
                  重试
                </button>
              </div>
            ) : videoStatus === 'pending' && fusionStatus === 'completed' ? (
              <div className={styles.placeholder}>
                <button
                  className={styles.generateButton}
                  onClick={() => onGenerateVideo(panelIndex)}
                >
                  生成视频
                </button>
              </div>
            ) : (
              <div className={styles.placeholder}>
                <span className={styles.placeholderText}>等待融合</span>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ---- 底部信息栏 ---- */}
      <div className={styles.infoBar}>
        <div className={styles.infoMeta}>
          {panelId && <span className={styles.panelId}>{panelId}</span>}
          {shotType && <span className={styles.shotType}>{shotType}</span>}
        </div>
        {(sceneDescription || dialogue) && (
          <div className={styles.infoDesc}>
            {sceneDescription && (
              <p className={styles.descText}>
                {truncate(sceneDescription, 60)}
              </p>
            )}
            {dialogue && (
              <p className={styles.descText}>
                <span className={styles.dialogueLabel}>"</span>
                {truncate(dialogue, 50)}
                <span className={styles.dialogueLabel}>"</span>
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default PanelVideoCard;
