import styles from './TransitionPanel.module.less';
import type { PanelStageStatus } from '../../../../services/types/episode.types';

interface TransitionPanelProps {
  status: PanelStageStatus;
  imageUrl: string | null;
  hasTailFrame: boolean;
}

export default function TransitionPanel({ status, imageUrl, hasTailFrame }: TransitionPanelProps) {
  if (status === 'pending') {
    return (
      <div className={styles.panel}>
        <p className={styles.hint}>融合图确认后自动处理{hasTailFrame ? '（含尾帧过渡）' : ''}</p>
      </div>
    );
  }

  if (status === 'generating') {
    return (
      <div className={styles.panel}>
        <div className={styles.spinner} />
        <p>正在生成过渡融合图...</p>
      </div>
    );
  }

  if (status === 'failed') {
    return (
      <div className={styles.panel}>
        <p className={styles.error}>过渡融合图生成失败（系统将自动重试）</p>
      </div>
    );
  }

  return (
    <div className={styles.panel}>
      {imageUrl && <img className={styles.image} src={imageUrl} alt="过渡融合图" />}
      {!hasTailFrame && <p className={styles.note}>首个分镜，无尾帧过渡</p>}
    </div>
  );
}
