import styles from './FusionPanel.module.less';
import type { PanelStageStatus } from '../../../../services/types/episode.types';

interface FusionPanelProps {
  status: PanelStageStatus;
  imageUrl: string | null;
  onGenerate: () => void;
  onConfirm: () => void;
}

export default function FusionPanel({ status, imageUrl, onGenerate, onConfirm }: FusionPanelProps) {
  if (status === 'pending') {
    return (
      <div className={styles.panel}>
        <p className={styles.hint}>背景图确认后将自动生成融合图</p>
      </div>
    );
  }

  if (status === 'generating') {
    return (
      <div className={styles.panel}>
        <div className={styles.spinner} />
        <p>正在生成融合图...</p>
      </div>
    );
  }

  if (status === 'failed') {
    return (
      <div className={styles.panel}>
        <p className={styles.error}>融合图生成失败</p>
        <button className={styles.secondaryBtn} onClick={onGenerate}>重新生成</button>
      </div>
    );
  }

  return (
    <div className={styles.panel}>
      {imageUrl && <img className={styles.image} src={imageUrl} alt="融合图" />}
      <div className={styles.actions}>
        <button className={styles.secondaryBtn} onClick={onGenerate}>重新生成</button>
        <button className={styles.primaryBtn} onClick={onConfirm}>确认并继续</button>
      </div>
    </div>
  );
}
