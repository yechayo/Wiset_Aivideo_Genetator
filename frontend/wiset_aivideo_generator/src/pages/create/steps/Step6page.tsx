import { useState, useCallback } from 'react';
import styles from './Step6page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';
import { useCreateStore } from '../../../stores/createStore';
import { startVideoAssembly } from '../../../services/projectService';

interface Step6pageProps extends StepContentProps {
  project: Project;
}

const Step6page = ({ project }: Step6pageProps) => {
  const { statusInfo } = useCreateStore();
  const [startingAssembly, setStartingAssembly] = useState(false);

  const projectId = project.projectId;
  const statusCode = statusInfo?.statusCode || '';
  const finalVideoUrl = statusInfo?.finalVideoUrl;

  // 开始视频合成
  const handleStartAssembly = useCallback(async () => {
    if (!projectId) return;
    const confirmed = window.confirm('确认后将开始视频合成，无法再修改分镜。是否继续？');
    if (!confirmed) return;
    setStartingAssembly(true);
    try {
      await startVideoAssembly(projectId);
    } catch (err: any) {
      alert(err?.response?.data?.message || err?.message || '启动视频合成失败');
    } finally {
      setStartingAssembly(false);
    }
  }, [projectId]);

  // PANEL_CONFIRMED：准备开始视频合成
  if (statusCode === 'PANEL_CONFIRMED') {
    return (
      <div className={styles.content}>
        <div className={styles.header}>
          <h1 className={styles.title}>视频合成</h1>
          <p className={styles.subtitle}>所有分镜已确认，可以开始视频合成</p>
        </div>
        <div className={styles.readyView}>
          <div className={styles.readyIcon}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <h2 className={styles.readyTitle}>分镜全部完成</h2>
          <p className={styles.readyDescription}>
            所有 {statusInfo?.panelTotalEpisodes || 0} 集分镜已确认完成，可以开始合成最终视频
          </p>
          <button
            className={styles.startAssemblyButton}
            onClick={handleStartAssembly}
            disabled={startingAssembly}
          >
            {startingAssembly ? '启动中...' : '开始视频合成'}
          </button>
        </div>
      </div>
    );
  }

  // VIDEO_ASSEMBLING / COMPLETED：显示合成进度或最终视频
  if (statusCode === 'VIDEO_ASSEMBLING' || statusCode === 'COMPLETED') {
    return (
      <div className={styles.content}>
        <div className={styles.header}>
          <h1 className={styles.title}>生成进度</h1>
          <p className={styles.subtitle}>查看生成进度和下载</p>
        </div>
        <div className={styles.completedView}>
          {statusCode === 'VIDEO_ASSEMBLING' && !finalVideoUrl && (
            <>
              <div className={styles.spinner}></div>
              <p className={styles.completedMessage}>视频合成中...</p>
            </>
          )}
          {finalVideoUrl ? (
            <>
              <div className={styles.completedIcon}>&#10003;</div>
              <p className={styles.completedMessage}>
                {statusCode === 'COMPLETED' ? '视频生产完成' : '视频合成完成'}
              </p>
              <div className={styles.videoContainer}>
                <video controls src={finalVideoUrl} />
              </div>
              <a
                className={styles.downloadButton}
                href={finalVideoUrl}
                download
                target="_blank"
                rel="noreferrer"
              >
                下载视频
              </a>
            </>
          ) : statusCode === 'VIDEO_ASSEMBLING' ? null : (
            <div className={styles.loadingState}>
              <div className={styles.spinner}></div>
              <p>正在处理视频...</p>
            </div>
          )}
        </div>
      </div>
    );
  }

  // 其他状态（兜底）
  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>生成进度</h1>
        <p className={styles.subtitle}>查看生成进度和下载</p>
      </div>
      <div className={styles.loadingState}>
        <div className={styles.spinner}></div>
        <p>正在加载生产状态...</p>
      </div>
    </div>
  );
};

export default Step6page;
