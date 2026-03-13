import styles from './Step2page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';

interface Step5pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step 5: 生成进度
 */
const Step5page = ({ project: _project, onComplete: _onComplete, onBack }: Step5pageProps) => {
  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>生成进度</h1>
        <p className={styles.subtitle}>
          查看生成进度和下载
        </p>
      </div>

      <div className={styles.emptyContent}>
        第五步内容开发中...
      </div>

      <div className={styles.buttonContainer}>
        <button className={styles.backButton} onClick={onBack}>
          返回上一步
        </button>
      </div>
    </div>
  );
};

export default Step5page;
