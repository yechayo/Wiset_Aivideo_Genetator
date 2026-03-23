import styles from './PanelToolbar.module.less';
import { useFusionStore } from '../../../../stores/fusionStore';

interface PanelToolbarProps {
  onAssignToPanel: () => void;
  canAssign: boolean;
}

const PanelToolbar = ({ onAssignToPanel, canAssign }: PanelToolbarProps) => {
  const {
    selectedPanelIndex,
    selectedCharacterIndex,
    panelOverlays,
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
