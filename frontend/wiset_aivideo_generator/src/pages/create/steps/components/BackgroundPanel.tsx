import styles from './BackgroundPanel.module.less';
import type { PanelStageStatus } from '../../../../services/types/episode.types';

interface BackgroundPanelProps {
  status: PanelStageStatus;
  imageUrl: string | null;
  prompt: string | null;
  onGenerate: () => void;
}

export default function BackgroundPanel({ status, imageUrl, prompt, onGenerate }: BackgroundPanelProps) {
  if (status === 'pending') {
    return (
      <div className={styles.panel}>
        <p className={styles.hint}>点击下方按钮生成背景图</p>
        {prompt && <p className={styles.prompt}>Prompt: {prompt}</p>}
        <button className={styles.primaryBtn} onClick={onGenerate}>生成背景图</button>
      </div>
    );
  }

  if (status === 'generating') {
    return (
      <div className={styles.panel}>
        <div className={styles.spinner} />
        <p>正在生成背景图...</p>
      </div>
    );
  }

  if (status === 'failed') {
    return (
      <div className={styles.panel}>
        <p className={styles.error}>背景图生成失败</p>
        <button className={styles.primaryBtn} onClick={onGenerate}>重新生成</button>
      </div>
    );
  }

  return (
    <div className={styles.panel}>
      {imageUrl && <img className={styles.image} src={imageUrl} alt="背景图" />}
    </div>
  );
}
