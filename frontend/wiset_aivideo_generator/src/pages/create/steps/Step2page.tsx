import { useEffect, useState } from 'react';
import styles from './Step2page.module.less';
import type { Project, Script } from '../../../services';
import { getScript, isApiSuccess } from '../../../services';
import type { StepContentProps } from '../types';
import { useProjectStore } from '../../../stores';

interface Step2pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step 2: 剧本编辑
 */
const Step2page = ({ project, onComplete, onBack }: Step2pageProps) => {
  const [script, setScript] = useState<Script | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const getProjectId = useProjectStore((state) => state.getProjectId);

  useEffect(() => {
    const fetchScript = async () => {
      // 获取项目 ID（优先使用 store 中的，回退到 props 中的）
      const projectId = getProjectId() || project.projectId || (project.id ? String(project.id) : null);

      if (!projectId) {
        setError('无法获取项目 ID');
        setIsLoading(false);
        return;
      }

      setIsLoading(true);
      setError(null);

      try {
        const result = await getScript(projectId);
        console.log('剧本数据:', result);

        if (isApiSuccess(result) && result.data) {
          setScript(result.data);
        } else {
          // 剧本可能还没有生成完成，使用空数据
          setScript(null);
          console.warn('剧本数据为空或未生成');
        }
      } catch (err) {
        console.error('获取剧本失败:', err);
        setError('获取剧本失败，请稍后重试');
      } finally {
        setIsLoading(false);
      }
    };

    fetchScript();
  }, [project, getProjectId]);

  // 处理确认
  const handleConfirm = () => {
    console.log('确认项目:', project);
    console.log('当前剧本:', script);
    // 完成此步骤，进入下一步
    onComplete();
  };

  return (
    <div className={styles.content}>
      {/* 标题区域 */}
      <div className={styles.header}>
        <h1 className={styles.title}>剧本编辑</h1>
        <p className={styles.subtitle}>
          编辑 AI 生成的剧本内容
        </p>
      </div>

      {/* 加载状态 */}
      {isLoading && (
        <div className={styles.loadingState}>
          <div className={styles.spinner}></div>
          <p>正在加载剧本...</p>
        </div>
      )}

      {/* 错误状态 */}
      {!isLoading && error && (
        <div className={styles.errorState}>
          <p>{error}</p>
          <button
            className={styles.retryButton}
            onClick={() => window.location.reload()}
          >
            重试
          </button>
        </div>
      )}

      {/* 剧本内容 */}
      {!isLoading && !error && (
        <div className={styles.scriptContainer}>
          {script ? (
            <>
              {/* 剧本标题 */}
              {script.title && (
                <div className={styles.scriptTitle}>
                  <h2>{script.title}</h2>
                </div>
              )}

              {/* 剧本内容 */}
              {script.content && (
                <div className={styles.scriptContent}>
                  <div className={styles.scriptCard}>
                    <h3 className={styles.cardTitle}>剧本内容</h3>
                    <p className={styles.cardText}>{script.content}</p>
                  </div>
                </div>
              )}

              {/* 场景列表 */}
              {script.scenes && script.scenes.length > 0 && (
                <div className={styles.scenesList}>
                  <h3 className={styles.scenesTitle}>分镜场景</h3>
                  {script.scenes.map((scene, index) => (
                    <div key={scene.id || index} className={styles.sceneCard}>
                      <div className={styles.sceneHeader}>
                        <span className={styles.sceneNumber}>场景 {index + 1}</span>
                        {scene.duration && (
                          <span className={styles.sceneDuration}>{scene.duration}秒</span>
                        )}
                      </div>
                      <p className={styles.sceneDescription}>{scene.description}</p>
                    </div>
                  ))}
                </div>
              )}

              {/* 空状态 */}
              {!script.title && !script.content && (!script.scenes || script.scenes.length === 0) && (
                <div className={styles.emptyState}>
                  <p>剧本内容为空</p>
                  <p className={styles.emptyHint}>AI 可能还在生成中，请稍后刷新</p>
                </div>
              )}
            </>
          ) : (
            <div className={styles.emptyState}>
              <p>暂无剧本数据</p>
              <p className={styles.emptyHint}>剧本可能还在生成中，请稍后刷新</p>
            </div>
          )}
        </div>
      )}

      {/* 按钮容器 */}
      <div className={styles.buttonContainer}>
        <button
          className={styles.backButton}
          onClick={onBack}
        >
          返回上一步
        </button>
        <button
          className={styles.confirmButton}
          onClick={handleConfirm}
          disabled={isLoading}
        >
          下一步
        </button>
      </div>
    </div>
  );
};

export default Step2page;
