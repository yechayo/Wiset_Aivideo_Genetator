import { useState, useCallback } from 'react';
import styles from './Step6page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';
import { mergeVideos } from '../../../services/projectService';
import { useCreateStore } from '../../../stores/createStore';

interface Step6pageProps extends StepContentProps {
  project: Project;
}

const Step6page = ({ project }: Step6pageProps) => {
  const projectId = project.projectId;
  const { statusInfo } = useCreateStore();
  const [merging, setMerging] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // 从后端状态获取合并结果
  const finalVideoUrl = statusInfo?.finalVideoUrl ?? null;
  const isCompleted = statusInfo?.statusCode === 'COMPLETED';

  const handleMerge = useCallback(async () => {
    if (!projectId || merging) return;

    setMerging(true);
    setErrorMessage(null);

    try {
      await mergeVideos(projectId);
      // 合并成功后后端会推进状态到 COMPLETED，轮询会自动更新 statusInfo
    } catch (e: any) {
      setErrorMessage(e?.response?.data?.message || e?.message || '拼接失败');
      setMerging(false);
    }
  }, [projectId, merging]);

  return (
    <div className={styles.content}>
      {/* 标题 */}
      <div className={styles.header}>
        <h1 className={styles.title}>视频拼接</h1>
        <p className={styles.subtitle}>拼接所有面板视频，自动去掉每段前5帧</p>
      </div>

      {/* 视频预览区 */}
      <div className={styles.videoContainer}>
        {isCompleted && finalVideoUrl ? (
          <video
            className={styles.videoPlayer}
            controls
            src={finalVideoUrl}
            preload="metadata"
          />
        ) : merging ? (
          <div className={styles.placeholder}>
            <div className={styles.spinner}></div>
            <p className={styles.placeholderText}>正在拼接中...</p>
          </div>
        ) : errorMessage ? (
          <div className={styles.placeholder}>
            <div className={styles.errorIcon}>⚠️</div>
            <p className={styles.errorText}>{errorMessage || '拼接失败，请重试'}</p>
          </div>
        ) : (
          <div className={styles.placeholder}>
            <div className={styles.placeholderIcon}>📹</div>
            <p className={styles.placeholderText}>等待拼接视频</p>
          </div>
        )}
      </div>

      {/* 操作按钮 */}
      <div className={styles.actions}>
        {isCompleted && finalVideoUrl ? (
          <a
            className={styles.downloadButton}
            href={finalVideoUrl || undefined}
            download
            target="_blank"
            rel="noreferrer"
          >
            下载视频
          </a>
        ) : errorMessage ? (
          <button className={styles.mergeButton} onClick={handleMerge} disabled={merging}>
            重试
          </button>
        ) : (
          <button className={styles.mergeButton} onClick={handleMerge} disabled={merging}>
            {merging ? '拼接中...' : '拼接视频'}
          </button>
        )}
      </div>
    </div>
  );
};

export default Step6page;
