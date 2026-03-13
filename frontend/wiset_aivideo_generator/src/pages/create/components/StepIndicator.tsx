import type { StepIndicatorProps } from '../types';
import styles from './StepIndicator.module.less';

/**
 * 步骤指示器组件
 * - 显示所有步骤
 * - 当前步骤高亮
 * - 已完成步骤可点击跳转
 * - 未完成步骤不可点击
 */
const StepIndicator = ({
  steps,
  currentStep,
  completedSteps,
  onStepClick,
}: StepIndicatorProps) => {
  const handleStepClick = (stepId: number) => {
    if (onStepClick && completedSteps.includes(stepId)) {
      onStepClick(stepId);
    }
  };

  const getStepItemClassName = (stepId: number) => {
    const isActive = currentStep >= stepId;
    const isCompleted = completedSteps.includes(stepId);
    const isClickable = onStepClick && isCompleted;

    return [
      styles.stepItem,
      isActive && styles.active,
      isCompleted && styles.completed,
      isClickable && styles.clickable,
    ]
      .filter(Boolean)
      .join(' ');
  };

  const getStepNumber = (stepId: number) => {
    // 已完成的步骤显示空（CSS会显示勾选）
    if (completedSteps.includes(stepId)) {
      return '';
    }
    return stepId;
  };

  return (
    <div className={styles.stepIndicator}>
      <div className={styles.stepContainer}>
        <div className={styles.stepList}>
          {steps.map((step, index) => (
            <div key={step.id} className={styles.stepItemGroup}>
              <div
                className={getStepItemClassName(step.id)}
                onClick={() => handleStepClick(step.id)}
                role="button"
                tabIndex={completedSteps.includes(step.id) ? 0 : -1}
                aria-current={currentStep === step.id ? 'step' : undefined}
                aria-label={`${step.label}${completedSteps.includes(step.id) ? '（已完成）' : ''}`}
              >
                <span className={styles.stepNumber}>{getStepNumber(step.id)}</span>
                <span>{step.label}</span>
              </div>
              {index < steps.length - 1 && (
                <span className={styles.stepArrow}>→</span>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default StepIndicator;
