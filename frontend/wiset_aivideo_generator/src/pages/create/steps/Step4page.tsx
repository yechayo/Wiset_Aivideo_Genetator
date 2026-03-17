import styles from './Step2page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';

interface Step4pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step 4: 生成配置
 */
const Step4page = ({ project, onComplete }: Step4pageProps) => {
  const handleNext = () => {
    const confirmed = window.confirm('确认后将开始生成，无法再返回修改。请确认配置无误后再继续。');
    if (!confirmed) return;
    console.log('开始生成:', project);
    onComplete();
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
        <button className={styles.confirmButton} onClick={handleNext}>
          开始生成
        </button>
      </div>
    </div>
  );
};

export default Step4page;
