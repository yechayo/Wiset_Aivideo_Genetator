import styles from './Step2page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';

interface Step4pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step 4: 生成配置
 */
const Step4page = ({ project, onComplete, onBack }: Step4pageProps) => {
  const handleNext = () => {
    console.log('开始生成:', project);
    onComplete();
  };

  const handleBack = () => {
    onBack();
  };

  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>生成配置</h1>
        <p className={styles.subtitle}>
          确认并开始生成
        </p>
      </div>

      <div className={styles.emptyContent}>
        第四步内容开发中...
      </div>

      <div className={styles.buttonContainer}>
        <button className={styles.backButton} onClick={handleBack}>
          返回上一步
        </button>
        <button className={styles.confirmButton} onClick={handleNext}>
          开始生成
        </button>
      </div>
    </div>
  );
};

export default Step4page;
