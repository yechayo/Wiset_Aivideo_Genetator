import styles from './Step5Card.module.less';
import type { StoryboardPanel } from '../../../../services';
import type { WorkflowPhase, SceneImageState } from '../../../../services/types/episode.types';

interface Step5CardProps {
  panel: StoryboardPanel;
  panelIndex: number;
  workflowPhase: WorkflowPhase;
  sceneImageState?: SceneImageState;
  fusionStatus: 'pending' | 'completed' | 'failed';
  fusionUrl?: string | null;
  videoStatus: 'pending' | 'generating' | 'completed' | 'failed';
  videoUrl?: string | null;
  onConfirm: () => void;
  onRegenerateScene: () => void;
  onAutoFusion: () => void;
  onManualFusion: () => void;
  onGenerateVideo: () => void;
}

const Step5Card = ({
  panel,
  panelIndex,
  workflowPhase,
  sceneImageState,
  fusionStatus,
  fusionUrl,
  videoStatus,
  videoUrl,
  onConfirm,
  onRegenerateScene,
  onAutoFusion,
  onManualFusion,
  onGenerateVideo,
}: Step5CardProps) => {
  // 阶段1：分镜审查态
  if (workflowPhase === 'review') {
    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.panelIndex}>#{panelIndex + 1}</span>
          <span className={styles.shotType}>{panel.shot_size} / {panel.camera_angle}</span>
        </div>
        <div className={styles.cardBody}>
          <div className={styles.field}>
            <span className={styles.label}>场景</span>
            <span className={styles.value}>{panel.scene}</span>
          </div>
          <div className={styles.field}>
            <span className={styles.label}>角色</span>
            <span className={styles.value}>{panel.characters}</span>
          </div>
          <div className={styles.field}>
            <span className={styles.label}>对话</span>
            <span className={styles.value}>"{panel.dialogue}"</span>
          </div>
        </div>
        <div className={styles.cardActions}>
          <button className={styles.primaryBtn} onClick={onConfirm}>确认</button>
          <button className={styles.secondaryBtn}>修订</button>
        </div>
      </div>
    );
  }

  // 阶段2：场景生成态
  if (workflowPhase === 'scene-generating') {
    const generating = sceneImageState?.generating ?? false;
    const failed = sceneImageState?.failed ?? false;
    const hasImage = !!sceneImageState?.url;

    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.panelIndex}>#{panelIndex + 1}</span>
          <span className={styles.shotType}>{panel.shot_size}</span>
        </div>
        <div className={styles.cardBody}>
          {generating && (
            <div className={styles.sceneGenerating}>
              <div className={styles.spinner} />
              <span>场景图生成中...</span>
            </div>
          )}
          {failed && (
            <div className={styles.sceneFailed}>
              <span>生成失败</span>
              <button onClick={onRegenerateScene}>重新生成</button>
            </div>
          )}
          {hasImage && !generating && (
            <>
              <img className={styles.sceneImage} src={sceneImageState!.url!} alt={`场景${panelIndex + 1}`} />
              <div className={styles.promptText}>
                <span className={styles.label}>Prompt</span>
                <span className={styles.value}>{sceneImageState!.prompt}</span>
              </div>
            </>
          )}
          {!hasImage && !generating && !failed && (
            <div className={styles.waitingScene}>
              <span>等待场景图生成</span>
            </div>
          )}
        </div>
        {/* 融合按钮（仅在有场景图时可用） */}
        <div className={styles.cardActions}>
          <button
            className={styles.primaryBtn}
            disabled={!hasImage || generating}
            onClick={onAutoFusion}
          >
            自动融合
          </button>
          <button
            className={styles.secondaryBtn}
            disabled={!hasImage || generating}
            onClick={onManualFusion}
          >
            手动融合
          </button>
        </div>
      </div>
    );
  }

  // 阶段3：融合态
  if (workflowPhase === 'fusion') {
    const hasImage = !!sceneImageState?.url;
    const hasFusion = fusionStatus === 'completed';

    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.panelIndex}>#{panelIndex + 1}</span>
          <span className={styles.shotType}>{panel.shot_size}</span>
        </div>
        <div className={styles.cardBody}>
          <div className={styles.sceneImageRow}>
            {hasImage ? (
              <img className={styles.sceneImage} src={sceneImageState!.url!} alt={`场景${panelIndex + 1}`} />
            ) : (
              <div className={styles.placeholder}>等待场景图</div>
            )}
          </div>
          {sceneImageState?.prompt && (
            <div className={styles.promptText}>
              <span className={styles.label}>Prompt</span>
              <span className={styles.value}>{sceneImageState!.prompt}</span>
            </div>
          )}
          {hasFusion && fusionUrl ? (
            <div className={styles.fusionImageWrapper}>
              <img className={styles.fusionImage} src={fusionUrl} alt={`融合图${panelIndex + 1}`} />
              <span className={styles.fusionLabel}>✓ 已融合</span>
            </div>
          ) : hasFusion ? (
            <div className={styles.fusionStatus}>
              <span>✓ 已融合</span>
            </div>
          ) : null}
        </div>
        <div className={styles.cardActions}>
          <button
            className={styles.primaryBtn}
            disabled={!hasImage}
            onClick={onAutoFusion}
          >
            {hasFusion ? '重新自动融合' : '自动融合'}
          </button>
          <button
            className={styles.secondaryBtn}
            disabled={!hasImage}
            onClick={onManualFusion}
          >
            手动融合
          </button>
        </div>
      </div>
    );
  }

  // 阶段4：视频生成态
  if (workflowPhase === 'video') {
    const hasImage = !!sceneImageState?.url;

    return (
      <div className={styles.card}>
        <div className={styles.cardHeader}>
          <span className={styles.panelIndex}>#{panelIndex + 1}</span>
          <span className={styles.shotType}>{panel.shot_size}</span>
        </div>
        <div className={styles.cardBody}>
          {/* 左侧：场景图 + 融合图 */}
          <div className={styles.leftCol}>
            {hasImage ? (
              <>
                <div className={styles.imageWithButton}>
                  <img className={styles.sceneImage} src={sceneImageState!.url!} alt={`场景${panelIndex + 1}`} />
                  <button className={styles.imageActionBtn} onClick={onRegenerateScene}>重新生成</button>
                </div>
                {sceneImageState?.prompt && (
                  <div className={styles.promptText}>
                    <span className={styles.label}>Prompt</span>
                    <span className={styles.value}>{sceneImageState!.prompt}</span>
                  </div>
                )}
              </>
            ) : (
              <div className={styles.placeholder}>等待场景图</div>
            )}
          </div>

          {/* 右侧：视频 */}
          <div className={styles.rightCol}>
            {videoStatus === 'completed' && videoUrl && (
              <video className={styles.videoPlayer} src={videoUrl} controls />
            )}
            {videoStatus === 'generating' && (
              <div className={styles.placeholder}>
                <div className={styles.spinner} />
                <span>视频生成中...</span>
              </div>
            )}
            {videoStatus === 'failed' && (
              <div className={styles.placeholder}>
                <span>生成失败</span>
                <button onClick={onGenerateVideo}>重试</button>
              </div>
            )}
            {videoStatus === 'pending' && (
              <div className={styles.placeholder}>
                <button className={styles.generateBtn} onClick={onGenerateVideo}>
                  生成视频
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }

  return null;
};

export default Step5Card;