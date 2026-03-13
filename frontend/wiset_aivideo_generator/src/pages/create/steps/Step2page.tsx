import styles from './Step2page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';

interface Step2pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step 2: 剧本编辑
 */
const Step2page = ({ project, onComplete, onBack }: Step2pageProps) => {
  // 处理确认
  const handleConfirm = () => {
    console.log('确认项目:', project);
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

      {/* 空白内容区域 - 后续扩展 */}
      <div className={styles.emptyContent}>
        第二步内容开发中...
      </div>

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
        >
          下一步
        </button>
      </div>
    </div>
  );
};

export default Step2page;
