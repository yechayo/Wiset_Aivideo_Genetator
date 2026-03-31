import { useState, useCallback } from 'react';
import styles from './Step6page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';
import { mergeVideos } from '../../../services/projectService';

interface Step6pageProps extends StepContentProps {
  project: Project;
}

type MergeStatus = 'idle' | 'merging' | 'completed' | 'failed';

const Step6page = ({ project }: Step6pageProps) => {
  const projectId = project.projectId;
  const [mergeStatus, setMergeStatus] = useState<MergeStatus>('idle');
  const [finalVideoUrl, setFinalVideoUrl] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const handleMerge = useCallback(async () => {
    if (!projectId) return;

    setMergeStatus('merging');
    setErrorMessage(null);

    try {
      const res = await mergeVideos(projectId);
      if (res.code === 200 && res.data?.finalVideoUrl) {
        setFinalVideoUrl(res.data.finalVideoUrl);
        setMergeStatus('completed');
      } else {
        setErrorMessage(res.message || '拼接失败');
        setMergeStatus('failed');
      }
    } catch (e: any) {
      setErrorMessage(e?.response?.data?.message || e?.message || '拼接失败');
      setMergeStatus('failed');
    }
  }, [projectId]);

  return (
    <div className={styles.content}>
      {/* 标题 */}
      <div className={styles.header}>
        <h1 className={styles.title}>视频拼接</h1>
        <p className={styles.subtitle}>拼接所有面板视频，自动去掉每段前5帧</p>
      </div>

      {/* 视频预览区 */}
      <div className={styles.videoContainer}>
        {mergeStatus === 'idle' && (
          <div className={styles.placeholder}>
            <div className={styles.placeholderIcon}>📹</div>
            <p className={styles.placeholderText}>等待拼接视频</p>
          </div>
        )}

        {mergeStatus === 'merging' && (
          <div className={styles.placeholder}>
            <div className={styles.spinner}></div>
            <p className={styles.placeholderText}>正在拼接中...</p>
          </div>
        )}

        {mergeStatus === 'failed' && (
          <div className={styles.placeholder}>
            <div className={styles.errorIcon}>⚠️</div>
            <p className={styles.errorText}>{errorMessage || '拼接失败，请重试'}</p>
          </div>
        )}

        {mergeStatus === 'completed' && finalVideoUrl && (
          <video
            className={styles.videoPlayer}
            controls
            src={finalVideoUrl}
            preload="metadata"
          />
        )}
      </div>

      {/* 操作按钮 */}
      <div className={styles.actions}>
        {mergeStatus === 'idle' && (
          <button className={styles.mergeButton} onClick={handleMerge}>
            拼接视频
          </button>
        )}

        {mergeStatus === 'merging' && (
          <button className={styles.mergeButton} disabled>
            拼接中...
          </button>
        )}

        {mergeStatus === 'failed' && (
          <button className={styles.mergeButton} onClick={handleMerge}>
            重试
          </button>
        )}

        {mergeStatus === 'completed' && (
          <a
            className={styles.downloadButton}
            href={finalVideoUrl || undefined}
            download
            target="_blank"
            rel="noreferrer"
          >
            下载视频
          </a>
        )}
      </div>
    </div>
  );
};

export default Step6page;
