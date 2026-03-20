import styles from './PanelToolbar.module.less';
import { useFusionStore } from '../../../../stores/fusionStore';
import type { OverlaySourceType } from '../../../../stores/fusionStore';

interface PanelToolbarProps {
  onAssignToPanel: () => void;
  canAssign: boolean;
}

const PanelToolbar = ({ onAssignToPanel, canAssign }: PanelToolbarProps) => {
  const {
    selectedPanelIndex,
    selectedCharacterIndex,
    panelOverlays,
    overlaySourceType,
    setOverlaySourceType,
    updateOverlayProperty,
    removeOverlayFromPanel,
  } = useFusionStore();

  const overlay = selectedPanelIndex !== null ? panelOverlays.get(selectedPanelIndex) : undefined;
  const panelLabel = selectedPanelIndex !== null ? `面板 ${selectedPanelIndex + 1}` : '';

  if (selectedPanelIndex === null) {
    return (
      <div className={styles.toolbar}>
        <span className={styles.noSelection}>点击网格中的面板进行选择</span>
      </div>
    );
  }

  return (
    <div className={styles.toolbar}>
      <div className={styles.toolbarInfo}>
        {panelLabel}
        {overlay ? (
          <> - <strong>{overlay.characterName}</strong></>
        ) : (
          <> - <span style={{ color: 'rgba(255,255,255,0.3)' }}>无覆盖</span></>
        )}
      </div>

      {/* Image source selector */}
      {overlay && (
        <div className={styles.sourceSelector}>
          {(['standard', 'threeView', 'expression'] as OverlaySourceType[]).map((type) => (
            <button
              key={type}
              className={`${styles.sourceButton} ${overlaySourceType === type ? styles.active : ''}`}
              onClick={() => {
                setOverlaySourceType(type);
                // Update overlay image URL based on new source type
                const { gridInfo } = useFusionStore.getState();
                if (!gridInfo) return;
                const char = gridInfo.characterReferences.find(
                  (c) => c.characterName === overlay.characterName
                );
                if (!char) return;
                const url =
                  type === 'standard'
                    ? char.standardImageUrl
                    : type === 'threeView'
                      ? char.threeViewGridUrl
                      : char.expressionGridUrl;
                if (url) {
                  updateOverlayProperty(selectedPanelIndex, { sourceType: type, imageUrl: url });
                }
              }}
            >
              {type === 'standard' ? '标准' : type === 'threeView' ? '三视图' : '表情'}
            </button>
          ))}
        </div>
      )}

      {/* Opacity slider */}
      {overlay && (
        <div className={styles.sliderGroup}>
          <span className={styles.sliderLabel}>透明度</span>
          <input
            type="range"
            className={styles.sliderInput}
            min={0}
            max={1}
            step={0.05}
            value={overlay.opacity}
            onChange={(e) => updateOverlayProperty(selectedPanelIndex, { opacity: parseFloat(e.target.value) })}
          />
          <span className={styles.sliderValue}>{Math.round(overlay.opacity * 100)}%</span>
        </div>
      )}

      {/* Scale slider */}
      {overlay && (
        <div className={styles.sliderGroup}>
          <span className={styles.sliderLabel}>缩放</span>
          <input
            type="range"
            className={styles.sliderInput}
            min={0.1}
            max={2}
            step={0.05}
            value={overlay.scale}
            onChange={(e) => updateOverlayProperty(selectedPanelIndex, { scale: parseFloat(e.target.value) })}
          />
          <span className={styles.sliderValue}>{overlay.scale.toFixed(2)}x</span>
        </div>
      )}

      <div className={styles.toolbarActions}>
        {/* Assign character to panel */}
        {!overlay && selectedCharacterIndex !== null && (
          <button className={styles.actionButton} onClick={onAssignToPanel} disabled={!canAssign}>
            分配角色
          </button>
        )}

        {/* Remove overlay */}
        {overlay && (
          <button
            className={`${styles.actionButton} ${styles.danger}`}
            onClick={() => removeOverlayFromPanel(selectedPanelIndex)}
          >
            移除
          </button>
        )}
      </div>
    </div>
  );
};

export default PanelToolbar;
