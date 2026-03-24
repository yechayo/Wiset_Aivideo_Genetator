import styles from './VideoPanel.module.less';
import type { PanelStageStatus } from '../../../../services/types/episode.types';

interface VideoPanelProps {
  status: PanelStageStatus;
  videoUrl: string | null;
  duration: number | null;
  onGenerate: () => void;
}

export default function VideoPanel({ status, videoUrl, duration, onGenerate }: VideoPanelProps) {
  if (status === 'pending') {
    return (
      <div className={styles.panel}>
        <p className={styles.hint}>过渡融合图完成后将自动生成视频</p>
        <button className={styles.primaryBtn} onClick={onGenerate}>生成视频</button>
      </div>
    );
  }

  if (status === 'generating') {
    return (
      <div className={styles.panel}>
        <div className={styles.spinner} />
        <p>正在生成视频...</p>
      </div>
    );
  }

  if (status === 'failed') {
    return (
      <div className={styles.panel}>
        <p className={styles.error}>视频生成失败</p>
        <button className={styles.primaryBtn} onClick={onGenerate}>重新生成</button>
      </div>
    );
  }

  return (
    <div className={styles.panel}>
      {videoUrl && (
        <div className={styles.videoContainer}>
          <video className={styles.video} src={videoUrl} controls />
          {duration && <span className={styles.duration}>{duration}s</span>}
        </div>
      )}
    </div>
  );
}
