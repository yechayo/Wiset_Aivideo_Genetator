import styles from './Step2page.module.less';
import type { Project } from '../../../services';
import type { StepContentProps } from '../types';

interface Step3pageProps extends StepContentProps {
  project: Project;
}

/**
 * Step 3: 角色配置
 */
const Step3page = ({ project, onComplete }: Step3pageProps) => {
  const handleNext = () => {
    const confirmed = window.confirm('确认后将进入下一步，无法再返回修改。请确认内容无误后再继续。');
    if (!confirmed) return;
    console.log('角色配置完成:', project);
    onComplete();
  };

  return (
    <div className={styles.content}>
      <div className={styles.header}>
        <h1 className={styles.title}>角色配置</h1>
        <p className={styles.subtitle}>
          配置角色形象
        </p>
      </div>

      <div className={styles.emptyContent}>
        第三步内容开发中...
      </div>

      <div className={styles.buttonContainer}>
        <button className={styles.confirmButton} onClick={handleNext}>
          下一步
        </button>
      </div>
    </div>
  );
};

export default Step3page;
